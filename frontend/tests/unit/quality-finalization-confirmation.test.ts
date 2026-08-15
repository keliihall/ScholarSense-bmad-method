// @ts-nocheck -- Vitest executes this Node-only source contract outside the browser bundle.
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const source = readFileSync(new URL(
  '../../src/domains/ingestion-quality/internal/QualityFuseRecoveryPanel.vue',
  import.meta.url,
), 'utf8');

describe('quality finalization irreversible confirmation', () => {
  it('opens a dedicated dialog before eligible and task-close execution', () => {
    expect(source).toContain('const finalDialog = ref<HTMLDialogElement>()');
    expect(source).toContain('finalDialog.value?.showModal()');
    expect(source).toContain('@click="openFinalizationConfirmation"');
    expect(source).toContain('确认进入 Eligible 并关闭同一任务');
    expect(source).toContain('@click="finalizeRecovery">确认执行不可逆终态</button>');
  });
});
