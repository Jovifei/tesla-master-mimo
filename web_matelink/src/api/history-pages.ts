/** Bounded sequential summary pagination. Never report a partial archive as complete. */
export async function loadSummaryPages<T>(
  fetchPage: (page: number, show: number) => Promise<unknown>,
  kind: 'drives' | 'charges',
  isCurrent: () => boolean,
  pageSize = 50,
  maxPages = 2000,
): Promise<T[]> {
  if (!Number.isSafeInteger(pageSize) || pageSize < 1 || !Number.isSafeInteger(maxPages) || maxPages < 1) {
    throw new Error('Invalid history pagination configuration');
  }
  const found = new Map<string, T>();
  const assertCurrent = () => { if (!isCurrent()) throw new Error('History account or server changed'); };
  for (let page = 1; page <= maxPages; page++) {
    assertCurrent();
    let body: unknown;
    try { body = await fetchPage(page, pageSize); }
    catch (error) { assertCurrent(); throw error; }
    assertCurrent();
    const envelope = asRecord(body);
    const data = asRecord(envelope?.data) ?? envelope;
    const items = data?.[kind];
    if (!Array.isArray(items)) throw new Error('Invalid history response');
    const meta = asRecord(data?.meta);
    const returnedPage = meta?.page;
    const returnedShow = meta?.show;
    const totalPages = meta?.total_pages;
    const total = meta?.total;
    if (returnedPage !== undefined && returnedPage !== page) throw new Error('History page mismatch');
    if (returnedShow !== undefined && (!positiveInteger(returnedShow))) throw new Error('Invalid history page size');
    if (totalPages !== undefined && !nonnegativeInteger(totalPages)) throw new Error('Invalid history page count');
    if (total !== undefined && !nonnegativeInteger(total)) throw new Error('Invalid history total');
    const size = returnedShow === undefined ? pageSize : returnedShow as number;
    const before = found.size;
    for (const row of items) {
      const record = asRecord(row);
      const id = record?.[kind === 'drives' ? 'drive_id' : 'charge_id'] ?? record?.id ?? record?.session_id;
      if ((typeof id !== 'string' && typeof id !== 'number') || String(id).trim() === '') {
        throw new Error('History row is missing a stable ID');
      }
      found.set(String(id), row as T);
    }
    const hasMore = totalPages === undefined ? items.length >= size : page < (totalPages as number);
    if ((items.length > 0 && found.size === before) || (hasMore && items.length === 0)) {
      throw new Error('History pagination made no progress');
    }
    if (!hasMore) {
      if (typeof total === 'number' && found.size !== total) throw new Error('History changed or is incomplete; retry');
      return [...found.values()];
    }
  }
  throw new Error('History page limit reached; narrow the requested range');
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function nonnegativeInteger(value: unknown): boolean {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
}
function positiveInteger(value: unknown): boolean { return nonnegativeInteger(value) && (value as number) > 0; }
