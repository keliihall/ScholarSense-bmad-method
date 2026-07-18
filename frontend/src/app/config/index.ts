/** Client-safe runtime configuration boundary. */
export type ClientRuntimeConfiguration = Readonly<Record<string, string>>;

export const SCHOLARSENSE_BASE_PATH = '/scholarsense/' as const;
