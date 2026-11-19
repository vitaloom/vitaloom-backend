const model = process.env.ASSISTANT_GEMINI_EMBEDDING_MODEL || 'gemini-embedding-2';
const generation = 'fixture-v1-' + model + '-768';
const filter = { status: 'APPROVED', language: 'vi', domain: 'APP_GUIDANCE', generation };
const collection = db.getCollection('assistant_sandbox_faq');
const index = collection.getSearchIndexes('assistant_fixture_v1')[0];
if (!index?.queryable) quit(2);

const expected = ['app-help-sleep-journal', 'app-help-water-progress', 'app-help-water-log'];
for (const id of expected) {
  const _id = id + '@1' + (model === 'gemini-embedding-001' ? ':' + model : '');
  const row = collection.findOne({ _id, ...filter });
  if (row?.embedding?.length !== 768) quit(2);
  const hits = collection.aggregate([{ $vectorSearch: {
    index: index.name, path: 'embedding', exact: true, limit: 4,
    queryVector: row.embedding, filter,
  }}]).toArray();
  if (!hits.some(hit => hit._id === _id)) quit(2);
}
print('PASS: 3 FAQ vectors are queryable (768 dimensions).');
