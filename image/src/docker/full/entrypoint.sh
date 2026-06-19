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
export HIVE_WAREHOUSE_PATH="${HIVE_WAREHOUSE_PATH:-/opt/hive/data/warehouse}"
export HIVE_SCRATCH_DIR="${HIVE_SCRATCH_DIR:-/opt/hive/scratch}"
export HIVE_QUERY_RESULTS_CACHE_DIRECTORY="${HIVE_WAREHOUSE_PATH:-/opt/hive/scratch/_resultscache_}"

export HIVE_ZOOKEEPER_QUORUM="${HIVE_ZOOKEEPER_QUORUM:-}"
export HIVE_LLAP_DAEMON_SERVICE_HOSTS="${HIVE_LLAP_DAEMON_SERVICE_HOSTS:-@llap0}"

export HIVE_SERVER2_TEZ_USE_EXTERNAL_SESSIONS="${HIVE_SERVER2_TEZ_USE_EXTERNAL_SESSIONS:-false}"
export TEZ_FRAMEWORK_MODE="${TEZ_FRAMEWORK_MODE:-}"
export TEZ_AM_REGISTRY_NAMESPACE="${TEZ_AM_REGISTRY_NAMESPACE:-/tez_am/server}"
export TEZ_AM_ZOOKEEPER_QUORUM="${TEZ_AM_ZOOKEEPER_QUORUM:-${HIVE_ZOOKEEPER_QUORUM}}"

envsubst < $HIVE_HOME/conf/core-site.xml.template > $HIVE_HOME/conf/core-site.xml
envsubst < $HIVE_HOME/conf/tez-site.xml.template > $HIVE_HOME/conf/tez-site.xml
# =========================================================================

: "${DB_DRIVER:=derby}"
: "${POSTGRES_HOST:=postgres}"
: "${POSTGRES_PORT:=5432}"
: "${POSTGRES_DB:=metastore}"
: "${POSTGRES_USER:=hive}"
: "${POSTGRES_PASSWORD:=hive}"
: "${MYSQL_HOST:=mysql}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_DB:=metastore}"
: "${MYSQL_USER:=hive}"
: "${MYSQL_PASSWORD:=hive}"

case "$DB_DRIVER" in
  postgres|postgresql)
    DB_DRIVER="postgres"
    : "${METASTORE_DB_CONNECTION_URL:=jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}}"
    : "${METASTORE_DB_CONNECTION_DRIVER:=org.postgresql.Driver}"
    : "${METASTORE_DB_CONNECTION_USER_NAME:=${POSTGRES_USER}}"
    : "${METASTORE_DB_CONNECTION_PASSWORD:=${POSTGRES_PASSWORD}}"
    ;;
  mysql)
    DB_DRIVER="mysql"
    : "${METASTORE_DB_CONNECTION_URL:=jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DB}?useSSL=false&allowPublicKeyRetrieval=true}"
    : "${METASTORE_DB_CONNECTION_DRIVER:=com.mysql.cj.jdbc.Driver}"
    : "${METASTORE_DB_CONNECTION_USER_NAME:=${MYSQL_USER}}"
    : "${METASTORE_DB_CONNECTION_PASSWORD:=${MYSQL_PASSWORD}}"
    ;;
  derby)
    : "${METASTORE_DB_CONNECTION_URL:=jdbc:derby:;databaseName=metastore_db;create=true}"
    if [ "$(echo "$HIVE_VER" | cut -d '.' -f1)" -lt "4" ]; then
      : "${METASTORE_DB_CONNECTION_DRIVER:=org.apache.derby.jdbc.EmbeddedDriver}"
    else
      : "${METASTORE_DB_CONNECTION_DRIVER:=org.apache.derby.iapi.jdbc.AutoloadedDriver}"
    fi
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
export METASTORE_DB_CONNECTION_URL_XML="${METASTORE_DB_CONNECTION_URL//&/&amp;}"

envsubst < $HIVE_HOME/conf/hive-site.xml.template > $HIVE_HOME/conf/hive-site.xml

SKIP_SCHEMA_INIT="${IS_RESUME:-false}"
[[ $VERBOSE = "true" ]] && VERBOSE_MODE="--verbose"

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

function append_java_opens {
  local -n _opts=$1
  local opens=(
    "--add-opens=java.base/java.lang=ALL-UNNAMED"
    "--add-opens=java.base/java.util=ALL-UNNAMED"
    "--add-opens=java.base/java.io=ALL-UNNAMED"
    "--add-opens=java.base/java.net=ALL-UNNAMED"
    "--add-opens=java.base/java.nio=ALL-UNNAMED"
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED"
    "--add-opens=java.base/java.util.regex=ALL-UNNAMED"
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
    "--add-opens=java.sql/java.sql=ALL-UNNAMED"
    "--add-opens=java.base/java.text=ALL-UNNAMED"
    "-Dnet.bytebuddy.experimental=true"
  )
  for opt in "${opens[@]}"; do
    if [[ " ${_opts} " != *" ${opt} "* ]]; then
      _opts="${_opts} ${opt}"
    fi
  done
}

function append_logging_opts {
  : "${HIVE_LOG_LEVEL:=INFO}"
  : "${HIVE_PERF_LOG_LEVEL:=INFO}"
  : "${HIVE_ROOT_LOGGER:=stdout}"
  HADOOP_CLIENT_OPTS="${HADOOP_CLIENT_OPTS:-} -Dhive.log.level=${HIVE_LOG_LEVEL} -Dhive.perflogger.log.level=${HIVE_PERF_LOG_LEVEL} -Dhive.root.logger=${HIVE_ROOT_LOGGER}"
  if [[ -n "${HIVE_LOG4J2_CONFIGURATION_FILE:-}" ]]; then
    HADOOP_CLIENT_OPTS="${HADOOP_CLIENT_OPTS} -Dlog4j.configurationFile=${HIVE_LOG4J2_CONFIGURATION_FILE}"
  fi
}

function run_llap {
  export LLAP_MEMORY_MB="${LLAP_MEMORY_MB:-1024}"
  export LLAP_EXECUTORS="${LLAP_EXECUTORS:-1}"

  envsubst < "$HIVE_HOME/conf/llap-daemon-site.xml.template" > "$HIVE_HOME/conf/llap-daemon-site.xml"

  export LLAP_DAEMON_LOG_DIR="${LLAP_DAEMON_LOG_DIR:-/tmp/llapDaemonLogs}"
  export LLAP_DAEMON_TMP_DIR="${LLAP_DAEMON_TMP_DIR:-/tmp/llapDaemonTmp}"
  export LOCAL_DIRS="${LOCAL_DIRS:-/tmp/llap-local}"
  mkdir -p "${LLAP_DAEMON_LOG_DIR}" "${LLAP_DAEMON_TMP_DIR}" "${LOCAL_DIRS}"

  # runLlapDaemon.sh expects jars under ${LLAP_DAEMON_HOME}/lib.
  # In this image, LLAP jars are under ${HIVE_HOME}/lib.
  export LLAP_DAEMON_HOME="${LLAP_DAEMON_HOME:-$HIVE_HOME}"
  export LLAP_DAEMON_CONF_DIR="${LLAP_DAEMON_CONF_DIR:-$HIVE_CONF_DIR}"
  export LLAP_DAEMON_USER_CLASSPATH="${LLAP_DAEMON_USER_CLASSPATH:-$TEZ_HOME/*:$TEZ_HOME/lib/*:$HADOOP_HOME/share/hadoop/common/*:$HADOOP_HOME/share/hadoop/common/lib/*:$HADOOP_HOME/share/hadoop/yarn/*:$HADOOP_HOME/share/hadoop/yarn/lib/*:$HADOOP_HOME/share/hadoop/hdfs/*:$HADOOP_HOME/share/hadoop/hdfs/lib/*:$HADOOP_HOME/share/hadoop/mapreduce/*:$HADOOP_HOME/share/hadoop/mapreduce/lib/*:$HADOOP_HOME/share/hadoop/tools/lib/*}"

  LLAP_DAEMON_OPTS="${LLAP_DAEMON_OPTS:-}"
  append_java_opens LLAP_DAEMON_OPTS

  if [[ -n "${LLAP_EXTRA_OPTS:-}" ]]; then
    export LLAP_DAEMON_OPTS="${LLAP_DAEMON_OPTS:-} ${LLAP_EXTRA_OPTS}"
  fi

  export LLAP_DAEMON_OPTS

  LLAP_RUN_SCRIPT="${HIVE_HOME}/scripts/llap/bin/runLlapDaemon.sh"
  if [ ! -x "${LLAP_RUN_SCRIPT}" ]; then
    echo "LLAP daemon launcher script not found at ${LLAP_RUN_SCRIPT}."
    exit 1
  fi
  exec "${LLAP_RUN_SCRIPT}" run "$@"
}

function run_tezam {
  : "${USER:=hive}"
  : "${LOCAL_DIRS:="/tmp"}"
  : "${LOG_DIRS:="/opt/tez/logs"}"
  : "${APP_SUBMIT_TIME_ENV:=$(($(date +%s) * 1000))}"
  : "${TEZ_AM_EXTERNAL_ID:="tez-session-${HOSTNAME:-tezam}"}"
  export USER LOCAL_DIRS LOG_DIRS APP_SUBMIT_TIME_ENV TEZ_AM_EXTERNAL_ID

  export HADOOP_HOME="${HADOOP_HOME:-/opt/hadoop}"
  export TEZ_HOME="${TEZ_HOME:-/opt/tez}"
  export HIVE_HOME="${HIVE_HOME:-/opt/hive}"
  export HADOOP_CONF_DIR="${HADOOP_CONF_DIR:-$HIVE_CONF_DIR}"
  export TEZ_CONF_DIR="${TEZ_CONF_DIR:-$HADOOP_CONF_DIR}"
  : "${TEZ_SNAPSHOT_HOME:=/opt/tez-snapshot}"
  if [[ ! -d "${TEZ_SNAPSHOT_HOME}" ]]; then
    echo "Tez snapshot home not found at ${TEZ_SNAPSHOT_HOME}. Rebuild image to prefetch snapshot artifacts."
    exit 1
  fi
  # service_plugins_descriptor.json references org.apache.hadoop.hive.llap.tezplugins.* (hive-llap-tez, etc.)
  tezam_cp="${HADOOP_CONF_DIR}:${TEZ_CONF_DIR}:${TEZ_SNAPSHOT_HOME}/*:${TEZ_HOME}/*:${TEZ_HOME}/lib/*:${HIVE_HOME}/lib/*:$("${HADOOP_HOME}/bin/hadoop" classpath)"

  local java_bin
  local tezam_java_opts
  java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  tezam_java_opts="${HADOOP_CLIENT_OPTS:-} -Dlog4j.configuration=file:${HIVE_CONF_DIR}/tez-log4j.properties"
  append_java_opens tezam_java_opts
  "${java_bin}" ${tezam_java_opts} -cp "${tezam_cp}" org.apache.tez.dag.app.DAGAppMaster --session "$@"
  local rc=$?
  if [[ ${rc} -ne 0 ]]; then
    echo "DAGAppMaster exited with code ${rc}. See logs above for details."
  fi
  exit "${rc}"
}

export HIVE_CONF_DIR=$HIVE_HOME/conf
if [ -d "${HIVE_CUSTOM_CONF_DIR:-}" ]; then
  find "${HIVE_CUSTOM_CONF_DIR}" -type f -exec \
    ln -sfn {} "${HIVE_CONF_DIR}"/ \;
  export HADOOP_CONF_DIR=$HIVE_CONF_DIR
  export TEZ_CONF_DIR=$HIVE_CONF_DIR
fi

append_logging_opts
export HADOOP_CLIENT_OPTS="${HADOOP_CLIENT_OPTS:-} -Xmx1G ${SERVICE_OPTS:-}"
if [[ "${SKIP_SCHEMA_INIT}" == "false" && ( "${SERVICE_NAME}" == "hiveserver2" || "${SERVICE_NAME}" == "metastore" ) ]]; then
  # handles schema initialization
  initialize_hive
fi

if [ "${SERVICE_NAME}" == "hiveserver2" ]; then
  TEZ_SNAPSHOT_HOME="${TEZ_SNAPSHOT_HOME:-/opt/tez-snapshot}"
  # bin/hive prepends all of $HIVE_HOME/lib/*.jar to $HADOOP_CLASSPATH, so any entry
  # we put first in HADOOP_CLASSPATH ends up after all Hive lib jars.  To get snapshot jars
  # truly first, symlink them into $HIVE_HOME/lib/ with a "0-" prefix: the for-loop glob in
  # bin/hive processes jars alphabetically, and ASCII '0' (48) sorts before 'a' (97), so these
  # symlinks become the very first jars on the classpath (right after the conf dir entries).
  if ls "${TEZ_SNAPSHOT_HOME}"/*.jar 1>/dev/null 2>&1; then
    for snap_jar in "${TEZ_SNAPSHOT_HOME}"/*.jar; do
      ln -sf "$snap_jar" "${HIVE_HOME}/lib/0-$(basename "$snap_jar")"
    done
  fi
  export HADOOP_CLASSPATH="$TEZ_HOME/*:$TEZ_HOME/lib/*:$HADOOP_CLASSPATH"
  exec "$HIVE_HOME/bin/hive" --skiphadoopversion --skiphbasecp --service "$SERVICE_NAME"
elif [ "${SERVICE_NAME}" == "metastore" ]; then
  export METASTORE_PORT=${METASTORE_PORT:-9083}
  if [[ -n "$VERBOSE_MODE" ]]; then
    exec "$HIVE_HOME/bin/hive" --skiphadoopversion --skiphbasecp "$VERBOSE_MODE" --service "$SERVICE_NAME"
  else
    exec "$HIVE_HOME/bin/hive" --skiphadoopversion --skiphbasecp --service "$SERVICE_NAME"
  fi
elif [ "${SERVICE_NAME}" == "llap" ]; then
  run_llap "$@"
elif [ "${SERVICE_NAME}" == "tezam" ]; then
  run_tezam "$@"
fi
