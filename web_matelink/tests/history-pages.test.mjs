import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { loadSummaryPages } from '../src/api/history-pages.ts';

const current = () => true;
function envelope(kind, page, total, show = 20) {
  const start = (page - 1) * show;
  return { data: { [kind]: Array.from({length: Math.max(0, Math.min(show, total-start))}, (_, i) => ({id:start+i+1})),
    meta: {page, show, total, total_pages: Math.ceil(total/show)} } };
}
for (const kind of ['drives', 'charges']) {
  test(`${kind}: 66 rows in four server-capped pages reach analytics`, async () => {
    const calls = [];
    const rows = await loadSummaryPages(async (page, show) => {calls.push([page,show]); return envelope(kind,page,66);},kind,current);
    assert.equal(rows.length,66); assert.deepEqual(calls.map(x=>x[0]),[1,2,3,4]);
  });
}
test('legacy API without meta terminates on a short page', async () => {
  const rows = await loadSummaryPages(async page => ({drives: page===1 ? [{id:1},{id:2}] : [{id:3}]}),'drives',current,2);
  assert.equal(rows.length,3);
});
test('empty archive returns no synthetic entries', async () => {
  assert.deepEqual(await loadSummaryPages(async () => envelope('charges',1,0),'charges',current),[]);
});
test('later page failure never returns partial history as all history', async () => {
  await assert.rejects(loadSummaryPages(async page => {if(page===2) throw new Error('offline');return envelope('drives',page,66);},'drives',current),/offline/);
});
test('repeated page fails instead of looping or double counting', async () => {
  await assert.rejects(loadSummaryPages(async page => ({data:{drives:[{id:1},{id:2}],meta:{page,show:2,total_pages:4}}}),'drives',current,2),/no progress/);
});
test('wrong returned page is rejected', async () => {
  await assert.rejects(loadSummaryPages(async () => envelope('drives',2,66),'drives',current),/page mismatch/);
});
test('account change after response is rejected', async () => {
  let active = true;
  await assert.rejects(loadSummaryPages(async () => {active=false;return envelope('drives',1,1);},'drives',()=>active),/account or server changed/);
});
test('invalid or missing list does not look like an empty archive', async () => {
  await assert.rejects(loadSummaryPages(async () => ({error:'bad_response'}),'drives',current),/Invalid history response/);
});
test('stable IDs deduplicate overlapping pages without losing distinct records', async () => {
  const rows = await loadSummaryPages(async page => ({data:{drives:page===1?[{drive_id:1},{drive_id:2}]:[{drive_id:2},{drive_id:3}],meta:{page,show:2,total:3,total_pages:2}}}),'drives',current,2);
  assert.equal(rows.length,3);
});
test('page limit is an explicit error', async () => {
  await assert.rejects(loadSummaryPages(async page => envelope('drives',page,66),'drives',current,20,1),/page limit/);
});
test('inconsistent total is not silently accepted by statistics', async () => {
  await assert.rejects(loadSummaryPages(async () => ({data:{drives:[{id:1}],meta:{page:1,show:20,total:2,total_pages:1}}}),'drives',current),/incomplete/);
});
test('production entry points use the paged loader for both lists', () => {
  const source = readFileSync(new URL('../src/api/client.ts',import.meta.url),'utf8');
  assert.match(source,/fetchCompleteHistory\(carId, 'drives'/);
  assert.match(source,/fetchCompleteHistory\(carId, 'charges'/);
  assert.match(source,/current\.apiToken === initial\.apiToken/);
  assert.match(source,/return loadSummaryPages/);
});
