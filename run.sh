#!/usr/bin/env bash
export LAUNCHPAD_BACKEND_CATALOG_GIT_REF=next

java -DdevMode=true -jar target/generator-swarm.jar
