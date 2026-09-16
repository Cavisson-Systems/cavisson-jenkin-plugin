#!/bin/bash
set -e

########################################
# Logging
########################################

LOG_LEVEL="${LOG_LEVEL:-INFO}"

_log_level_value() {
    case "${1^^}" in
        ERROR) echo 0 ;;
        INFO)  echo 1 ;;
        DEBUG) echo 2 ;;
        *)     echo 1 ;;
    esac
}

CURRENT_LOG_LEVEL=$(_log_level_value "$LOG_LEVEL")

_log() {
    local level="${1^^}"
    shift

    local required=$(_log_level_value "$level")

    if [ "$CURRENT_LOG_LEVEL" -ge "$required" ]; then
        printf "%s %-5s %s\n" \
            "$(date '+%H:%M:%S')" \
            "$level" \
            "$*"
    fi
}

log_error() { _log ERROR "$@"; }
log_debug()  { _log INFO  "$@"; }
log_debug() { _log DEBUG "$@"; }

########################################
# Parse parameters
########################################
while [[ "$#" -gt 0 ]]; do
  case $1 in
    --hostUrl) HOST_URL="$2"; shift ;;
    --userToken) USER_TOKEN="$2"; shift ;;
    --userName) USER_NAME="$2"; shift ;;
    --sonarToken) SONAR_TOKEN="$2"; shift ;;
    --token) cavissonToken="$2"; shift ;;
    --projectKey) PROJECT_KEY="$2"; shift ;;
    --organization) ORG="$2"; shift ;;
    --solution) SOLUTION_DIR="$2"; shift ;;
    --isSonarCloud) IS_SONARCLOUD="$2"; shift ;;
    *) log_error "Unknown parameter: $1"; exit 1 ;;
  esac
  shift
done

########################################
# Validate parameters
########################################
if [[ -z "$HOST_URL" || -z "$SONAR_TOKEN" || -z "$PROJECT_KEY" || -z "$USER_TOKEN" || -z "$USER_NAME" ]]; then
  log_error "Required parameters: --hostUrl --sonarToken --projectKey --userToken -- userName"
  exit 1
fi

#########################################
#Finding base directory for solution
#########################################
if [[ -n "$SOLUTION_DIR" ]]; then
  log_debug "Scanning specific solution: $SOLUTION_DIR"

  if [[ ! -d "$SOLUTION_DIR" ]]; then
    log_error "Provided solution directory does not exist"
    exit 1
  fi

  BASE_DIR="$SOLUTION_DIR"
else
  log_debug "No solution provided. Scanning full repository"
  BASE_DIR="."
fi

log_debug "Base directory for scanning: $BASE_DIR"


#######################################
# Installing JQ
#######################################
# Package-manager installs (apt-get/yum/dnf/apk) require root, which the Jenkins agent user
# normally doesn't have. Instead, fetch the official static jq binary (no root needed) and cache
# it under $HOME, mirroring how the SonarScanner CLI is self-installed/cached further below.
JQ_VERSION="1.7.1"
JQ_DIR="$HOME/.cav-tools/jq"

install_jq() {
    if command -v jq >/dev/null 2>&1; then
        echo "[INFO] jq already installed: $(jq --version)"
        return 0
    fi

    if [[ -x "$JQ_DIR/jq" ]]; then
        export PATH="$JQ_DIR:$PATH"
        echo "[INFO] jq already installed: $(jq --version)"
        return 0
    fi

    echo "[INFO] jq is required but was not found. Downloading static jq $JQ_VERSION binary..."

    local arch
    case "$(uname -m)" in
        x86_64) arch="amd64" ;;
        aarch64|arm64) arch="arm64" ;;
        *)
            echo "[ERROR] Unsupported architecture for jq download: $(uname -m)"
            return 1
            ;;
    esac

    mkdir -p "$JQ_DIR"

    curl -sSLo "$JQ_DIR/jq" \
        "https://github.com/jqlang/jq/releases/download/jq-${JQ_VERSION}/jq-linux-${arch}" || {
        echo "[ERROR] Failed to download jq."
        return 1
    }

    chmod +x "$JQ_DIR/jq"

    export PATH="$JQ_DIR:$PATH"

    command -v jq >/dev/null 2>&1 || {
        echo "[ERROR] jq installation failed."
        return 1
    }

    echo "[INFO] jq installed: $(jq --version)"
}
install_jq || {
    log_error "Failed to install jq. Please install it manually."
    exit 1
}

########################################
# Get login
########################################

# first check the response of api, if it fails then we can assume that the token is invalid and exit with error message
#RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -u "$SONAR_TOKEN:" "$HOST_URL/api/users/current")
#if [[ "$RESPONSE" -ne 200 ]]; then
#  echo "ERROR: Authentication failed with provided token. HTTP status code: $RESPONSE"
#  exit 1
#fi
# if the response is successful then we can proceed to get the login from the api response
#Login

#LOGIN=$(curl -s -u "$SONAR_TOKEN:" "$HOST_URL/api/users/current" | jq -r '.login')
#echo "Authenticated as: $LOGIN"


########################################
# Create project if missing
########################################
create_project_if_missing() {

  if [[ "$IS_SONARCLOUD" == true ]]; then
    PROJECT_EXISTS=$(curl -s -u "$SONAR_TOKEN:" \
      "$HOST_URL/api/projects/search?projects=$PROJECT_KEY&organization=$ORG" \
      | jq '.components | length')
  else
    PROJECT_EXISTS=$(curl -s -u "$SONAR_TOKEN:" \
      "$HOST_URL/api/projects/search?projects=$PROJECT_KEY" \
      | jq '.components | length')
  fi

  #log_debug "Project exists: $PROJECT_EXISTS"

  if [[ "$PROJECT_EXISTS" == "0" ]]; then

    log_debug "Creating project $PROJECT_KEY"

    if [[ "$IS_SONARCLOUD" == true ]]; then

      curl -u "$SONAR_TOKEN:" \
      -X POST "$HOST_URL/api/projects/create" \
      -d "organization=$ORG" \
      -d "project=$PROJECT_KEY" \
      -d "name=$PROJECT_KEY"

    else
      #echo " Project creation URL: $HOST_URL/api/projects/create?project=$PROJECT_KEY&name=$PROJECT_KEY"
      curl -sS -u "$USER_TOKEN:" \
	      -X POST "$HOST_URL/api/projects/create?project=$PROJECT_KEY&name=$PROJECT_KEY&visibility=private" \
        >/dev/null

      #echo "Project creation response: $r"

      #echo "Permission call : $HOST_URL/api/permissions/add_user?login=$USER_NAME&projectKey=$PROJECT_KEY&permission=scan"

      curl -sS -u "$SONAR_TOKEN:" \
	      -X POST "$HOST_URL/api/permissions/add_user?login=$USER_NAME&projectKey=$PROJECT_KEY&permission=scan" \
        >/dev/null

      #echo "Permission assignment response: $p"
    fi
  else
    log_debug "Project already exists"
  fi
}

########################################
# Install scanners
########################################
log_debug "Installing scanners..."

#dotnet tool install --global dotnet-sonarscanner || true
#export PATH="$PATH:$HOME/.dotnet/tools"

# install pytest 
#pip install pytest || true

########################################
#install sonar-scanner
#########################################

log_debug "Installing SonarScanner CLI..."

SONAR_DIR="$HOME/.sonar-scanner"

if [ ! -d "$SONAR_DIR" ]; then
  mkdir -p "$SONAR_DIR"

  curl -sSLo /tmp/sonar-scanner.zip \
    https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-5.0.1.3006-linux.zip

  unzip -q /tmp/sonar-scanner.zip -d "$SONAR_DIR"

  mv $SONAR_DIR/sonar-scanner-* $SONAR_DIR/current
fi

export PATH="$SONAR_DIR/current/bin:$PATH"

#log_debug "SonarScanner CLI version:"
log_debug "SonarScanner version:"
sonar-scanner --version | while read line; do
    log_debug "$line"
done

########################################
#which sonar-scanner || log_debug "sonar-scanner not found"

SCANNER_PATH=$(command -v sonar-scanner)

if [[ -n "$SCANNER_PATH" ]]; then
    log_debug "SonarScanner path: $SCANNER_PATH"
else
    log_error "sonar-scanner not found"
fi

########################################
# Detect technologies
########################################

DOTNET_SOLUTIONS=$(find "$BASE_DIR" -name "*.sln" || true)
MAVEN_PROJECTS=$(find "$BASE_DIR" -name "pom.xml" || true)
NODE_PROJECTS=$(find "$BASE_DIR" -name "package.json" || true)
PY_PROJECTS=$(find "$BASE_DIR" -name "requirements.txt" || true)
GO_PROJECTS=$(find "$BASE_DIR" -name "go.mod" || true)
GRADLE_PROJECTS=$(find "$BASE_DIR" \( -name "build.gradle" -o -name "build.gradle.kts" \) || true)

log_debug "Detected .NET solutions: $DOTNET_SOLUTIONS"
log_debug "Detected Maven projects: $MAVEN_PROJECTS"
log_debug "Detected NodeJS projects: $NODE_PROJECTS"
log_debug "Detected Python projects: $PY_PROJECTS"
log_debug "Detected Go projects: $GO_PROJECTS"
log_debug "Detected Gradle projects: $GRADLE_PROJECTS"

########################################
# Build stage
########################################

log_debug "====================================="
log_debug "Building monorepo projects"
log_debug "====================================="

build_dotnet() {

  if ! command -v dotnet >/dev/null 2>&1; then
      log_debug "Skipping .NET build (dotnet SDK not installed)"
      return
  fi

  for SLN in $DOTNET_SOLUTIONS; do
    DIR=$(dirname "$SLN")
    log_debug "Building .NET: $SLN"
    (
      cd "$DIR"
      dotnet restore "$SLN" >/dev/null 2>&1
      dotnet build "$SLN" >/dev/null 2>&1
      dotnet test "$SLN" >/dev/null 2>&1 || true
    ) &
  done
}


build_maven() {
  if ! command -v mvn >/dev/null 2>&1; then
    log_debug "Skipping Maven build (Maven not installed)"
    return
  fi
  for POM in $MAVEN_PROJECTS; do
    DIR=$(dirname "$POM")
    log_debug "Building Maven: $POM"
    (
      cd "$DIR"
      mvn -B clean verify >/dev/null 2>&1
    ) &
  done
}

build_node() {
 
  if ! command -v npm >/dev/null 2>&1; then
      log_debug "Skipping NodeJS build (npm not installed)"
      return
  fi

  for PKG in $NODE_PROJECTS; do
    DIR=$(dirname "$PKG")
    log_debug "Building NodeJS: $PKG"
    (
      cd "$DIR"
      npm install >/dev/null 2>&1
      npm test >/dev/null 2>&1 || true
    ) &
  done
}

build_python() {

   if ! command -v pip >/dev/null 2>&1; then
      log_debug "Skipping Python build (pip not installed)"
      return
  fi

  for REQ in $PY_PROJECTS; do
    DIR=$(dirname "$REQ")
    log_debug "Building Python: $REQ"
    (
      cd "$DIR"
      pip install -r requirements.txt >/dev/null 2>&1 || true
      pytest >/dev/null 2>&1 || true
    ) &
  done
}

build_go() {

  if ! command -v go >/dev/null 2>&1; then
      log_debug "Skipping Go build (Go not installed)"
      return
  fi

  for MOD in $GO_PROJECTS; do
    DIR=$(dirname "$MOD")
    log_debug "Building Go: $MOD"
    (
      cd "$DIR"
      go mod tidy >/dev/null 2>&1
      go test ./... >/dev/null 2>&1 || true
    ) &
  done
}


build_gradle() {

  for GRADLE in $GRADLE_PROJECTS; do
    DIR=$(dirname "$GRADLE")
    log_debug "Building Gradle: $GRADLE"
    (
      cd "$DIR"
      if [ -f "./gradlew" ]; then
        chmod +x ./gradlew
        ./gradlew clean build -x test >/dev/null 2>&1 || true
      else
        gradle clean build -x test >/dev/null 2>&1 || true
      fi
    ) &
  done
}

build_dotnet
build_maven
build_node
build_python
build_go
build_gradle

wait

########################################
# Create project
########################################
create_project_if_missing


#########################################
# comment for c code analysis, as it requires compile_commands.json file, we can enable it in future once we have a way to generate compile_commands.json file for c/c++ projects in monorepo, for now we can disable it to avoid scan failure due to missing compile_commands.json file

CFAMILY_OPTIONS=""

COMPILE_DB=$(find "$BASE_DIR" -name "compile_commands.json" | head -1)

if [[ -n "$COMPILE_DB" ]]; then
  log_debug "C/C++ compilation database detected: $COMPILE_DB"
  CFAMILY_OPTION="-Dsonar.cfamily.compile-commands=$COMPILE_DB"
else
  log_debug "No compilation database found. Disabling C/C++ analysis."

  CFAMILY_OPTION="-Dsonar.c.file.suffixes=- -Dsonar.cpp.file.suffixes=- -Dsonar.objc.file.suffixes=-"
fi
########################################

log_debug "CFAMILY_OPTION: $CFAMILY_OPTION"

########################################
# Run Sonar scan
########################################

log_debug "====================================="
log_debug "Running Sonar analysis"
log_debug "====================================="

log_debug "All details:"
log_debug "HOST_URL: $HOST_URL"
log_debug "PROJECT_KEY: $PROJECT_KEY"
log_debug "ORG: $ORG"
log_debug "IS_SONARCLOUD: $IS_SONARCLOUD"  
log_debug "CFAMILY_OPTION: $CFAMILY_OPTION"

EXCLUSIONS="**/node_modules/**,**/.git/**,**/target/**,**/build/**,**/dist/**"

SCANNER_OPTS=(
  -Dsonar.projectKey=$PROJECT_KEY
  -Dsonar.sources=$BASE_DIR
  -Dsonar.host.url=$HOST_URL
)

# Only add sonar.java.binaries if compiled class directories actually exist on disk.
# Covers Maven (target/classes) and Gradle (build/classes/java|kotlin|groovy/main).
JAVA_BINARIES=$(find "$BASE_DIR" -type d \( \
  -path "*/target/classes" \
  -o -path "*/build/classes/java/main" \
  -o -path "*/build/classes/kotlin/main" \
  -o -path "*/build/classes/groovy/main" \
\) 2>/dev/null | paste -sd "," -)
if [[ -n "$JAVA_BINARIES" ]]; then
  log_debug "Found Java class directories: $JAVA_BINARIES"
  SCANNER_OPTS+=("-Dsonar.java.binaries=$JAVA_BINARIES")
else
  log_debug "No compiled class directories found — skipping sonar.java.binaries"
  if ! command -v mvn >/dev/null 2>&1 && \
       ! command -v gradle >/dev/null 2>&1 && \
       [ ! -f "$BASE_DIR/gradlew" ]; then

        log_debug "Java build tools not found. Excluding Java sources."

        EXCLUSIONS="$EXCLUSIONS,**/*.java"

    else

        log_debug "Java build tool detected but no compiled classes found."

    fi
fi

SCANNER_OPTS+=("-Dsonar.exclusions=$EXCLUSIONS")

if [ "$IS_SONARCLOUD" = "true" ]; then
  SCANNER_OPTS+=(-Dsonar.organization=$ORG)
  SCANNER_OPTS+=(-Dsonar.token=$USER_TOKEN)
else
  VERSION=$(curl -s $HOST_URL/api/server/version | cut -d. -f1)

  if [ "$VERSION" -ge 10 ]; then
    SCANNER_OPTS+=(-Dsonar.token=$USER_TOKEN)
  else
    SCANNER_OPTS+=(-Dsonar.login=$USER_TOKEN)
  fi
  
fi

if [ -n "$CFAMILY_OPTION" ]; then
  SCANNER_OPTS+=($CFAMILY_OPTION)
fi


log_debug "Running Sonar analysis..."
#echo "sonar-scanner -X ${SCANNER_OPTS[*]}"

LOG_FILE="$WORKSPACE/sonar-scanner.log"

if [ -z "$WORKSPACE" ]; then
    LOG_FILE="./sonar-scanner.log"
fi


set +e

sonar-scanner "${SCANNER_OPTS[@]}" > "$LOG_FILE" 2>&1
SCAN_EXIT_CODE=$?

set -e

log_debug "Scanner exit code: $SCAN_EXIT_CODE"

if [ $SCAN_EXIT_CODE -ne 0 ]; then
    log_error "SonarScanner failed."
    log_error "Last 100 lines from sonar-scanner.log:"
    tail -100 "$LOG_FILE"
    exit $SCAN_EXIT_CODE
fi
