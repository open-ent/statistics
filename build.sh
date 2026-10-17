#!/bin/bash

MVN_OPTS="-Duser.home=/var/maven -T 4"

if [ ! -e node_modules ]
then
  mkdir node_modules
fi

case `uname -s` in
  MINGW* | Darwin*)
    USER_UID=1000
    GROUP_GID=1000
    ;;
  *)
    if [ -z ${USER_UID:+x} ]
    then
      USER_UID=`id -u`
      GROUP_GID=`id -g`
    fi
esac

# Options
NO_DOCKER=""
SPRINGBOARD="recette"
for i in "$@"
do
case $i in
  -s=*|--springboard=*)
  SPRINGBOARD="${i#*=}"
  shift
  ;;
  --no-docker*)
  NO_DOCKER="true"
  shift
  ;;
  *)
  ;;
esac
done

init() {
  me=`id -u`:`id -g`
  echo "DEFAULT_DOCKER_USER=$me" > .env

  # If CLI_VERSION is empty set to latest
  if [ -z "$CLI_VERSION" ]; then
    CLI_VERSION="latest"
  fi
  # Create a build.compose.yaml file from following template
  cat <<EOF > build.compose.yaml
services:
  edifice-cli:
    image: opendigitaleducation/edifice-cli:$CLI_VERSION
    user: "$DEFAULT_DOCKER_USER"
EOF
  # Copy /root/edifice from edifice-cli container to host machine
  docker compose -f build.compose.yaml create edifice-cli
  docker compose -f build.compose.yaml cp edifice-cli:/root/edifice ./edifice
  docker compose -f build.compose.yaml rm -fsv edifice-cli
  rm -f build.compose.yaml
  chmod +x edifice
  ./edifice version $EDIFICE_CLI_DEBUG_OPTION
}

test () {
  docker compose run --rm maven mvn $MVN_OPTS test
}

# Interface React (frontend/, Vite) : construite sur l'hôte puis rangée dans les ressources du module, pour que
# `./build.sh install` produise à lui seul le jar complet (AngularJS + React) — aucune copie à la main.
# Ancien bundle supprimé d'abord et échec franc : jamais un vieux bundle embarqué en silence.
# Prérequis : Node >= 18 (corepack) et NODE_AUTH_TOKEN (paquets @open-ent privés).
buildReact () {
  local fe dir
  for fe in frontend; do
    [ -f "$fe/package.json" ] || continue
    dir=$(dirname "$fe")
    echo "[buildReact] Construction de l'interface React ($fe)"
    (
      cd "$fe" || exit 1
      rm -rf dist
      corepack pnpm install --frozen-lockfile || exit 1
      corepack pnpm build || exit 1
      ls dist/public/*.js >/dev/null 2>&1 || { echo "[buildReact] aucun bundle dans dist/public : build React invalide"; exit 1; }
    ) || { echo "[buildReact] ÉCHEC ($fe)"; exit 1; }
    mkdir -p "$dir/src/main/resources/public"
    cp -r "$fe/dist/public/." "$dir/src/main/resources/public/"
  done
}

clean () {
  if [ "$NO_DOCKER" = "true" ] ; then
    rm -rf node_modules
    rm -f yarn.lock
    mvn clean
  else
    docker compose run --rm maven mvn $MVN_OPTS clean
  fi
}

buildNode () {
  #jenkins
  echo "[buildNode] Get branch name from jenkins env..."
  BRANCH_NAME=`echo $GIT_BRANCH | sed -e "s|origin/||g"`
  if [ "$BRANCH_NAME" = "" ]; then
    echo "[buildNode] Get branch name from git..."
    BRANCH_NAME=`git branch | sed -n -e "s/^\* \(.*\)/\1/p"`
  fi
  if [ "$BRANCH_NAME" = "" ]; then
    echo "[buildNode] Branch name should not be empty!"
    exit -1
  fi

  if [ "$BRANCH_NAME" = 'master' ]; then
      echo "[buildNode] Use entcore version from package.json ($BRANCH_NAME)"
      case `uname -s` in
        MINGW*)
          if [ "$NO_DOCKER" = "true" ] ; then
            yarn install --no-bin-links && yarn upgrade entcore && node_modules/gulp/bin/gulp.js build
          else
            docker compose run --rm -u "$USER_UID:$GROUP_GID" node sh -c "yarn install --no-bin-links --legacy-peer-deps --force && yarn upgrade entcore && node_modules/gulp/bin/gulp.js build"
          fi
          ;;
        *)
          if [ "$NO_DOCKER" = "true" ] ; then
            yarn install && yarn upgrade entcore && node_modules/gulp/bin/gulp.js build
          else
            docker compose run --rm -u "$USER_UID:$GROUP_GID" node sh -c "yarn install --legacy-peer-deps --force && yarn upgrade entcore && node_modules/gulp/bin/gulp.js build"
          fi
      esac
  else
      echo "[buildNode] Use entcore tag $BRANCH_NAME"
      case `uname -s` in
        MINGW*)
          if [ "$NO_DOCKER" = "true" ] ; then
            yarn install && yarn upgrade entcore && node_modules/gulp/bin/gulp.js build
          else
            docker compose run --rm -u "$USER_UID:$GROUP_GID" node sh -c "yarn install --no-bin-links --legacy-peer-deps --force && npm rm --no-save entcore && yarn install --no-save entcore@dev && node_modules/gulp/bin/gulp.js build"
          fi
          ;;
        *)
          if [ "$NO_DOCKER" = "true" ] ; then
            yarn install --no-bin-links && yarn upgrade entcore && node_modules/gulp/bin/gulp.js build
          else
            docker compose run --rm -u "$USER_UID:$GROUP_GID" node sh -c "yarn install --legacy-peer-deps --force && npm rm --no-save entcore && yarn install --no-save entcore@dev && node_modules/gulp/bin/gulp.js build"
          fi
      esac
  fi
}

buildGradle () {
  if [ "$NO_DOCKER" = "true" ] ; then
    mvn install
  else
    docker compose run --rm maven mvn $MVN_OPTS install -DskipTests
  fi
}

publish () {
  if [ "$NO_DOCKER" = "true" ] ; then
    mvn deploy
  else
    version=`docker compose run --rm maven mvn $MVN_OPTS help:evaluate -Dexpression=project.version -q -DforceStdout`
    level=`echo $version | cut -d'-' -f3`
    case "$level" in
      *SNAPSHOT) export nexusRepository='snapshots' ;;
      *)         export nexusRepository='releases' ;;
    esac

    docker compose run --rm  maven mvn $MVN_OPTS -DrepositoryId=ode-$nexusRepository -DskipTests --settings /var/maven/.m2/settings.xml deploy
  fi
}

watch () {
  if [ "$NO_DOCKER" = "true" ] ; then
    node_modules/gulp/bin/gulp.js watch --springboard=../recette
  else
    docker compose run --rm -u "$USER_UID:$GROUP_GID" node sh -c "node_modules/gulp/bin/gulp.js watch --springboard=/home/node/$SPRINGBOARD"
  fi
}

for param in "$@"
do
  case $param in
    init)
      init
      ;;
    clean)
      clean
      ;;
    buildNode)
      buildNode
      ;;
    buildGradle)
      buildGradle
      ;;
    install)
      buildReact
      buildNode && buildGradle
      ;;
    watch)
      watch
      ;;
    publish)
      publish
      ;;
    test)
      test
      ;;
    *)
      echo "Invalid argument : $param"
  esac
  if [ ! $? -eq 0 ]; then
    exit 1
  fi
done