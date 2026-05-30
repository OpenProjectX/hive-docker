#!/bin/bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -x

# =========================================================================
# DYNAMIC JAR LOADER (AWS/S3 Support)
# =========================================================================
STAGING_DIR="/tmp/ext-jars"

# Checks if /tmp/ext-jars is mounted (via Docker volume).
if [ -d "$STAGING_DIR" ]; then
  if ls "$STAGING_DIR"/*.jar 1> /dev/null 2>&1; then
    echo "--> Copying custom jars from volume to Hive..."
    cp -vf "$STAGING_DIR"/*.jar "${HIVE_HOME}/lib/"
  else
    echo "--> Volume mounted at $STAGING_DIR, but no jars found."
  fi
fi

# =========================================================================
# REPLACE ${VARS} in the template
# =========================================================================
: "${HIVE_WAREHOUSE_PATH:=/opt/hive/data/warehouse}"
export HIVE_WAREHOUSE_PATH

envsubst < $HIVE_HOME/conf/core-site.xml.template > $HIVE_HOME/conf/core-site.xml
# =========================================================================

: "${DB_DRIVER:=derby}"
: "${POSTGRES_HOST:=postgres}"
: "${POSTGRES_PORT:=5432}"
: "${POSTGRES_DB:=metastore}"
: "${POSTGRES_USER:=hive}"
: "${POSTGRES_PASSWORD:=hive}"

case "$DB_DRIVER" in
  postgres|postgresql)
    DB_DRIVER="postgres"
    : "${METASTORE_DB_CONNECTION_URL:=jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}}"
    : "${METASTORE_DB_CONNECTION_DRIVER:=org.postgresql.Driver}"
    : "${METASTORE_DB_CONNECTION_USER_NAME:=${POSTGRES_USER}}"
    : "${METASTORE_DB_CONNECTION_PASSWORD:=${POSTGRES_PASSWORD}}"
    ;;
  derby)
    : "${METASTORE_DB_CONNECTION_URL:=jdbc:derby:;databaseName=metastore_db;create=true}"
    : "${METASTORE_DB_CONNECTION_DRIVER:=org.apache.derby.iapi.jdbc.AutoloadedDriver}"
    : "${METASTORE_DB_CONNECTION_USER_NAME:=APP}"
    : "${METASTORE_DB_CONNECTION_PASSWORD:=mine}"
    ;;
  *)
    : "${METASTORE_DB_CONNECTION_URL:=}"
    : "${METASTORE_DB_CONNECTION_DRIVER:=}"
    : "${METASTORE_DB_CONNECTION_USER_NAME:=}"
    : "${METASTORE_DB_CONNECTION_PASSWORD:=}"
    ;;
esac
export DB_DRIVER
export METASTORE_DB_CONNECTION_URL
export METASTORE_DB_CONNECTION_DRIVER
export METASTORE_DB_CONNECTION_USER_NAME
export METASTORE_DB_CONNECTION_PASSWORD

envsubst < $HIVE_HOME/conf/metastore-site.xml.template > $HIVE_HOME/conf/metastore-site.xml

SKIP_SCHEMA_INIT="${IS_RESUME:-false}"
[[ $VERBOSE = "true" ]] && VERBOSE_MODE="--verbose" || VERBOSE_MODE=""

function initialize_hive {
  COMMAND="-initOrUpgradeSchema"
  if [ "$(echo "$HIVE_VER" | cut -d '.' -f1)" -lt "4" ]; then
     COMMAND="-${SCHEMA_COMMAND:-initSchema}"
  fi
  SCHEMA_ARGS=(-dbType "$DB_DRIVER" "$COMMAND")
  if [ -n "$METASTORE_DB_CONNECTION_URL" ]; then
    SCHEMA_ARGS+=(-url "$METASTORE_DB_CONNECTION_URL")
  fi
  if [ -n "$METASTORE_DB_CONNECTION_DRIVER" ]; then
    SCHEMA_ARGS+=(-driver "$METASTORE_DB_CONNECTION_DRIVER")
  fi
  if [ -n "$METASTORE_DB_CONNECTION_USER_NAME" ]; then
    SCHEMA_ARGS+=(-userName "$METASTORE_DB_CONNECTION_USER_NAME")
  fi
  if [ -n "$METASTORE_DB_CONNECTION_PASSWORD" ]; then
    SCHEMA_ARGS+=(-passWord "$METASTORE_DB_CONNECTION_PASSWORD")
  fi
  if [ -n "$VERBOSE_MODE" ]; then
    SCHEMA_ARGS+=("$VERBOSE_MODE")
  fi
  "$HIVE_HOME/bin/schematool" "${SCHEMA_ARGS[@]}"
  if [ $? -eq 0 ]; then
    echo "Initialized Hive Metastore Server schema successfully.."
  else
    echo "Hive Metastore Server schema initialization failed!"
    exit 1
  fi
}

export HIVE_CONF_DIR=$HIVE_HOME/conf
if [ -d "${HIVE_CUSTOM_CONF_DIR:-}" ]; then
  find "${HIVE_CUSTOM_CONF_DIR}" -type f -exec \
    ln -sfn {} "${HIVE_CONF_DIR}"/ \;
  export HADOOP_CONF_DIR=$HIVE_CONF_DIR
fi

export HADOOP_CLIENT_OPTS="$HADOOP_CLIENT_OPTS -Xmx1G $SERVICE_OPTS"
if [[ "${SKIP_SCHEMA_INIT}" == "false" ]]; then
  # handles schema initialization
  initialize_hive
fi

export METASTORE_PORT=${METASTORE_PORT:-9083}
exec "$HIVE_HOME/bin/start-metastore"
