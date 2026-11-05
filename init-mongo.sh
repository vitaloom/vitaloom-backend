#!/bin/bash
set -euo pipefail

mongosh --quiet \
  --username "$MONGO_INITDB_ROOT_USERNAME" \
  --password "$MONGO_INITDB_ROOT_PASSWORD" \
  --authenticationDatabase admin <<'EOF'
const databaseName = process.env.APP_DB;
const applicationUser = process.env.APP_USER;
const applicationPassword = process.env.APP_PASSWORD;

if (!databaseName || !applicationUser || !applicationPassword) {
    throw new Error("APP_DB, APP_USER and APP_PASSWORD must all be configured.");
}

const applicationDatabase = db.getSiblingDB(databaseName);
applicationDatabase.createUser({
    user: applicationUser,
    pwd: applicationPassword,
    roles: [{role: "readWrite", db: databaseName}]
});
EOF
