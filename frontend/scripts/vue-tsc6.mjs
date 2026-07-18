import { createRequire } from 'node:module';


const require = createRequire(import.meta.url);
const typescriptAlias = require('typescript/package.json');
const compilerFallback = require('@typescript/old/package.json');

if (typescriptAlias.name !== '@typescript/typescript6' || typescriptAlias.version !== '6.0.2') {
  throw new Error('TYPESCRIPT_ALIAS_DRIFT');
}
if (compilerFallback.name !== 'typescript' || compilerFallback.version !== '6.0.2') {
  throw new Error('TYPESCRIPT_COMPILER_DRIFT');
}

const { run } = require('vue-tsc');
run(require.resolve('@typescript/old/lib/tsc.js'));
