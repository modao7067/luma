#!/usr/bin/env node
import {mkdtemp, mkdir, writeFile, link, rm, stat, lstat, readFile, readdir} from 'node:fs/promises';
const root = await mkdtemp('/root/native/dsh-atomic-link-');
const staging = `${root}/staging`, temporary = `${staging}/output.tmp`, published = `${root}/output.txt`;
await mkdir(staging); await writeFile(temporary, 'owned-payload\n');
const result = {fixture: root, operations: []};
async function observe() {
  const values = {};
  for (const [label, action] of [
    ['isFile', async () => (await stat(published)).isFile()],
    ['isSymlink', async () => (await lstat(published)).isSymbolicLink()],
    ['readText', () => readFile(published, 'utf8')],
    ['stagingEntries', () => readdir(staging)],
  ]) {
    try { values[label] = await action(); }
    catch (error) { values[label] = {code: error.code, error: error.message}; }
  }
  return values;
}
await link(temporary, published);
result.afterPublish = await observe();
try {
  await rm(staging, {recursive: true, force: true});
  result.operations.push({operation: 'removeStagingDirectoryRecursively', status: 'ok'});
} catch (error) {
  result.operations.push({operation: 'removeStagingDirectoryRecursively', code: error.code, error: error.message});
}
result.afterStagingCleanup = await observe();
console.log(JSON.stringify(result, null, 2));
