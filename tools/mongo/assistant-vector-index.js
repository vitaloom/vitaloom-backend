const collectionName = 'assistant_sandbox_faq';
const indexName = 'assistant_fixture_v1';
if (db.getCollectionInfos({ name: collectionName }).length === 0) {
  db.createCollection(collectionName);
}

const collection = db.getCollection(collectionName);
if (collection.getSearchIndexes(indexName).length === 0) {
  collection.createSearchIndex(indexName, 'vectorSearch', {
    fields: [
      { type: 'vector', path: 'embedding', numDimensions: 768, similarity: 'cosine' },
      ...['status', 'language', 'domain', 'generation'].map(path => ({ type: 'filter', path })),
    ],
  });
}

printjson(collection.getSearchIndexes(indexName).map(({ name, status, queryable }) =>
  ({ name, status, queryable })));
