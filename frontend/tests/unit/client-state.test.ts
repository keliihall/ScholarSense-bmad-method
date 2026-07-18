import { QueryClient } from '@tanstack/vue-query';
import { describe, expect, it, vi } from 'vitest';

import {
  VolatileClientState,
  canSubmitOnlineCommand,
  createQueryKey,
  recoverAfterConnectivity,
} from '../../src/app/state/volatile-client-state';
import type { QueryKeyParams } from '../../src/app/state/volatile-client-state';


const recoveryContext = {
  accountId: 'account-1',
  sessionId: 'session-1',
  connectionGeneration: 1,
  commandId: 'command-1',
} as const;

describe('volatile client state boundary', () => {
  it('creates the only approved query-key shape', () => {
    const params = Object.freeze({ page: 1, aggregateVersion: 7 });
    expect(createQueryKey('clue-care', 'work-items', params)).toEqual([
      'clue-care',
      'work-items',
      params,
    ]);
    expect(Object.isFrozen(createQueryKey('clue-care', 'work-items', params))).toBe(true);
  });

  it('defensively clones and recursively freezes query params', () => {
    const params = {
      page: 1,
      filter: { status: 'open' },
      tags: ['urgent'],
    };
    const key = createQueryKey('clue-care', 'work-items', params);
    params.page = 2;
    params.filter.status = 'closed';
    params.tags.push('changed');

    expect(key[2]).toEqual({
      page: 1,
      filter: { status: 'open' },
      tags: ['urgent'],
    });
    expect(Object.isFrozen(key[2])).toBe(true);
    expect(Object.isFrozen(key[2].filter)).toBe(true);
    expect(Object.isFrozen(key[2].tags)).toBe(true);

    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    expect(() => createQueryKey('clue-care', 'cyclic', cyclic as QueryKeyParams))
      .toThrow('QUERY_KEY_PARAM_INVALID');
    expect(() => createQueryKey('clue-care', 'invalid-number', {
      page: Number.NaN,
    })).toThrow('QUERY_KEY_PARAM_INVALID');
    expect(() => createQueryKey('clue-care', 'invalid-object', {
      date: new Date(),
    } as unknown as QueryKeyParams)).toThrow('QUERY_KEY_PARAM_INVALID');
  });

  it.each(['logout', 'account-switch', 'refresh', 'webview-destroyed', 'host-session-invalid'] as const)(
    'clears query cache and drafts on %s',
    (event) => {
      const queryClient = new QueryClient();
      queryClient.setQueryData(['clue-care', 'work-items', {}], { forbidden: 'object' });
      const state = new VolatileClientState(queryClient);
      state.setDraft('draft-1', { note: 'volatile only' });

      state.handleLifecycle(event);

      expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
      expect(state.draftCount).toBe(0);
    },
  );

  it('blocks offline commands without returning object information', () => {
    const offline = {
      ...recoveryContext,
      online: false,
      explicitRetryRequested: false,
      aggregateVersion: 0,
    };
    expect(canSubmitOnlineCommand(offline)).toEqual({
      allowed: false,
      message: '网络不可用，请恢复连接后显式重试。',
    });
    expect(JSON.stringify(canSubmitOnlineCommand(offline))).not.toContain('student');
    expect(canSubmitOnlineCommand({
      ...recoveryContext,
      online: true,
      explicitRetryRequested: true,
      aggregateVersion: 0,
    }).allowed).toBe(false);
    expect(() => canSubmitOnlineCommand({
      ...offline,
      online: 'false',
    } as unknown as Parameters<typeof canSubmitOnlineCommand>[0]))
      .toThrow('ONLINE_COMMAND_GATE_INVALID');
  });

  it('reauthenticates, reauthorizes and refreshes version before explicit retry', async () => {
    const calls: string[] = [];
    const authenticate = vi.fn().mockImplementation(async () => { calls.push('authenticate'); });
    const authorize = vi.fn().mockImplementation(async () => { calls.push('authorize'); });
    const refreshAggregateVersion = vi.fn().mockImplementation(async () => {
      calls.push('refreshAggregateVersion');
      return 11;
    });

    const result = await recoverAfterConnectivity({
      ...recoveryContext,
      authenticate,
      authorize,
      refreshAggregateVersion,
    });

    expect(authenticate).toHaveBeenCalledOnce();
    expect(authorize).toHaveBeenCalledOnce();
    expect(refreshAggregateVersion).toHaveBeenCalledOnce();
    expect(calls).toEqual(['authenticate', 'authorize', 'refreshAggregateVersion']);
    expect(result).toEqual({ aggregateVersion: 11, requiresExplicitRetry: true });
    expect(canSubmitOnlineCommand({
      ...recoveryContext,
      online: true,
      recovery: result,
      explicitRetryRequested: false,
      aggregateVersion: 11,
    }).allowed).toBe(false);
    expect(canSubmitOnlineCommand({
      ...recoveryContext,
      online: true,
      recovery: result,
      explicitRetryRequested: true,
      aggregateVersion: 11,
    })).toEqual({ allowed: true });
    expect(canSubmitOnlineCommand({
      ...recoveryContext,
      online: false,
      recovery: result,
      explicitRetryRequested: true,
      aggregateVersion: 11,
    }).allowed).toBe(false);
  });

  it('rejects forged recovery proof and invalid refreshed versions', async () => {
    const valid = await recoverAfterConnectivity({
      ...recoveryContext,
      authenticate: vi.fn().mockResolvedValue(undefined),
      authorize: vi.fn().mockResolvedValue(undefined),
      refreshAggregateVersion: vi.fn().mockResolvedValue(11),
    });
    const forged = { ...valid } as typeof valid;
    expect(canSubmitOnlineCommand({
      ...recoveryContext,
      online: true,
      recovery: forged,
      explicitRetryRequested: true,
      aggregateVersion: 11,
    }).allowed).toBe(false);

    for (const aggregateVersion of [-1, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
      await expect(recoverAfterConnectivity({
        ...recoveryContext,
        authenticate: vi.fn().mockResolvedValue(undefined),
        authorize: vi.fn().mockResolvedValue(undefined),
        refreshAggregateVersion: vi.fn().mockResolvedValue(aggregateVersion),
      })).rejects.toThrow('RECOVERY_AGGREGATE_VERSION_INVALID');
    }
  });

  it('binds one-use recovery proof to account, session, connection, command and version', async () => {
    const result = await recoverAfterConnectivity({
      ...recoveryContext,
      authenticate: vi.fn().mockResolvedValue(undefined),
      authorize: vi.fn().mockResolvedValue(undefined),
      refreshAggregateVersion: vi.fn().mockResolvedValue(11),
    });
    const baseGate = {
      ...recoveryContext,
      online: true,
      recovery: result,
      explicitRetryRequested: true,
      aggregateVersion: 11,
    };
    for (const mismatch of [
      { accountId: 'account-2' },
      { sessionId: 'session-2' },
      { connectionGeneration: 2 },
      { commandId: 'command-2' },
      { aggregateVersion: 12 },
    ]) {
      expect(canSubmitOnlineCommand({ ...baseGate, ...mismatch }).allowed).toBe(false);
    }
    expect(canSubmitOnlineCommand(baseGate)).toEqual({ allowed: true });
    expect(canSubmitOnlineCommand(baseGate).allowed).toBe(false);
  });

  it('revokes recovery proof on lifecycle clear and newer connection recovery', async () => {
    const steps = {
      authenticate: vi.fn().mockResolvedValue(undefined),
      authorize: vi.fn().mockResolvedValue(undefined),
      refreshAggregateVersion: vi.fn().mockResolvedValue(11),
    };
    const first = await recoverAfterConnectivity({ ...recoveryContext, ...steps });
    const secondContext = { ...recoveryContext, connectionGeneration: 2 };
    const second = await recoverAfterConnectivity({ ...secondContext, ...steps });
    expect(canSubmitOnlineCommand({
      ...recoveryContext,
      online: true,
      recovery: first,
      explicitRetryRequested: true,
      aggregateVersion: 11,
    }).allowed).toBe(false);

    const state = new VolatileClientState(new QueryClient());
    state.handleLifecycle('account-switch');
    expect(canSubmitOnlineCommand({
      ...secondContext,
      online: true,
      recovery: second,
      explicitRetryRequested: true,
      aggregateVersion: 11,
    }).allowed).toBe(false);
  });
});
