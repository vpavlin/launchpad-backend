#!/usr/bin/env bash
export LAUNCHPAD_BACKEND_CATALOG_GIT_REF=v10

java -DdevMode=true -jar target/generator-swarm.jar
