const fs = require('fs');
const specs = JSON.parse(fs.readFileSync(process.env.SEED_DIRECTORY + '/indexes.json', 'utf8'));
const hashes = db.runCommand({ dbHash: 1, collections: specs.map(spec => spec.collection) });
if (!hashes.ok) throw new Error(hashes.errmsg);

if (process.env.SEED_PHASE === 'preflight') {
  const missing = [];
  for (const spec of specs) {
    if (!db.getCollection(spec.collection).findOne({}, { _id: 1 })) {
      missing.push(spec.collection);
    } else if (hashes.collections[spec.collection] !== spec.bsonHash) {
      throw new Error(`${db.getName()}.${spec.collection} differs from the snapshot. Existing documents have been preserved. Use a fresh local vector volume to install the snapshot.`);
    }
  }
  print(JSON.stringify(missing));
} else {
  for (const spec of specs) {
    const collection = db.getCollection(spec.collection);
    const { collection: name, count, definition } = spec;
    if (hashes.collections[name] !== spec.bsonHash) {
      throw new Error(`${name}: restored BSON checksum differs from the snapshot.`);
    }
    const dimensions = definition.fields.find(field => field.type === 'vector').numDimensions;
    const valid = collection.countDocuments({ $expr: {
      $eq: [{ $cond: [{ $isArray: '$embedding' }, { $size: '$embedding' }, -1] }, dimensions],
    } });
    if (valid !== count || collection.countDocuments({}) !== count) {
      throw new Error(`${name}: expected ${count} documents with ${dimensions}-dimensional embeddings.`);
    }
    if (!collection.getSearchIndexes(spec.name).length) {
      collection.createSearchIndex(spec.name, spec.type, definition);
    }
  }

  const deadline = Date.now() + 1000 * Number(process.env.SEED_TIMEOUT_SECONDS);
  for (const spec of specs) {
    const collection = db.getCollection(spec.collection);
    while (true) {
      const index = collection.getSearchIndexes(spec.name)[0];
      if (index?.queryable && index.status === 'READY') break;
      if (index?.status === 'FAILED' || Date.now() >= deadline) {
        throw new Error(`${spec.name}: index did not become ready (status: ${index?.status}).`);
      }
      sleep(1000);
    }

    const sample = collection.findOne({}, { embedding: 1 });
    const hits = collection.aggregate([
      { $vectorSearch: {
        index: spec.name, path: 'embedding', exact: true, limit: spec.count,
        queryVector: sample.embedding,
      } },
      { $project: { _id: 1 } },
    ]).toArray();
    if (hits.length !== spec.count || !hits.some(hit => hit._id.toString() === sample._id.toString())) {
      throw new Error(`${spec.name}: vector search returned ${hits.length} of ${spec.count} expected matches.`);
    }
    print(`PASS: ${spec.collection} - ${spec.count} vectors queryable through ${spec.name}`);
  }
}
