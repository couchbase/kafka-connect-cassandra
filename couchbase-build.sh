#!/bin/bash -e

MVN_VERSION=3.9.2

usage() {
    echo "Usage: $0 -p PRODUCT -r RELEASE -v VERSION -b BLD_NUM"
    exit 1
}

while getopts ":p:r:v:b:h?" opt; do
    case $opt in
        p) PRODUCT=$OPTARG ;;
        r) RELEASE=$OPTARG ;;
        v) VERSION=$OPTARG ;;
        b) BLD_NUM=$OPTARG ;;
        h|?) usage ;;
        :) echo "-${OPTARG} requires an argument"
           usage
           ;;
    esac
done

if [ -z "${PRODUCT}" -o -z "${RELEASE}" -o \
     -z "${VERSION}" -o -z "${BLD_NUM}" ]; then
    usage
fi

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "${SCRIPT_DIR}"

if ! type mvn >& /dev/null; then
    TOOLS_DIR="$(pwd)/../tools"
    mkdir -p "${TOOLS_DIR}"
    cbdep install -d "${TOOLS_DIR}" mvn ${MVN_VERSION}
    export PATH=${TOOLS_DIR}/mvn-${MVN_VERSION}/bin:${PATH}
fi

PRODUCT_VERSION="${VERSION}.${BLD_NUM}"

# This is a little weird
./install-artifacts.sh

# The pom.xml uses ${project.version} to also specify the version of
# debezium to use, so we have to remember the current version and
# then override it at build time in order to set product.version.
debezium_version=$(
    xmllint pom.xml --xpath '/*[local-name() = "project"]/*[local-name() = "version"]/text()'
)
mvn -e versions:set -Dversion.debezium=${debezium_version} -DnewVersion="${PRODUCT_VERSION}"
mvn -e -Dversion.debezium=${debezium_version} clean verify -Dquick

# Place desired output jars into dist/ directory at root of repo sync.
# Update this script if the set of desired jars change.
DIST_DIR="$(pwd)/../dist"
mkdir -p "${DIST_DIR}"
cp \
    cassandra-3/target/debezium-connector-cassandra-3-${PRODUCT_VERSION}.jar \
    cassandra-3/target/debezium-connector-cassandra-3-${PRODUCT_VERSION}-jar-with-dependencies.jar \
    cassandra-3/target/debezium-connector-cassandra-3-${PRODUCT_VERSION}-sources.jar \
    cassandra-4/target/debezium-connector-cassandra-4-${PRODUCT_VERSION}.jar \
    cassandra-4/target/debezium-connector-cassandra-4-${PRODUCT_VERSION}-jar-with-dependencies.jar \
    cassandra-4/target/debezium-connector-cassandra-4-${PRODUCT_VERSION}-sources.jar \
    dse/target/debezium-connector-dse-${PRODUCT_VERSION}.jar \
    dse/target/debezium-connector-dse-${PRODUCT_VERSION}-jar-with-dependencies.jar \
    dse/target/debezium-connector-dse-${PRODUCT_VERSION}-sources.jar \
    "${DIST_DIR}"
