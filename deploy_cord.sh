#!/usr/bin/env bash
set -e

REMOTE_USER="gers"
REMOTE_HOST="87.242.101.169"   # подставь сюда
REMOTE_APP_DIR="/home/gers/cord/back"
REMOTE_BIN_DIR="$REMOTE_APP_DIR/bin"
PROFILE="dev"                    # dev / prod и т.п.

echo "=== BUILD JAR ==="
mvn clean package -DskipTests

JAR_FILE=$(ls target/cord.jar | head -n 1)

if [ -z "$JAR_FILE" ]; then
  echo "ERROR: JAR not found in target/"
  exit 1
fi

echo "=== COPY TO SERVER ==="
scp "$JAR_FILE" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_BIN_DIR}/cord-upload.jar"

echo "=== REMOTE DEPLOY ==="
ssh "${REMOTE_USER}@${REMOTE_HOST}" "cd ${REMOTE_APP_DIR} && ./deploy_cord.sh ${PROFILE}"

echo "=== DONE ==="