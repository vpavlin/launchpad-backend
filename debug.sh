#!/usr/bin/env bash
export LAUNCHPAD_BACKEND_CATALOG_GIT_REF=stopgap-release

java -Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005 -DdevMode=true -jar target/generator-swarm.jar
