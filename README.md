# Fabric8 Generator Backend

This is the back end service for the [fabric8-generator](https://github.com/fabric8io/fabric8-generator). 

The backend runs within a WildFly Swarm container and is called from the [fabric8-ui](https://github.com/fabric8io/fabric8-ui) front end to create new apps or import projects from github.

## Environment variables

To run this project you need to point the back end at KeyCloak and an OpenShift cluster. 

You can point the back end at the production KeyCloak and OpenShift cluster via:
```
export OPENSHIFT_API_URL=https://api.starter-us-east-2.openshift.com:443
export KEYCLOAK_SAAS_URL=https://sso.openshift.io/
```
Then call `./run.sh` or `./debug.sh` as below.

### Build this project:

```bash
$ ./build.sh
```

### Debugging this project


```bash
$ ./debug.sh
```

### Running this project

```bash
$ ./run.sh
```

## Using fabric8-ui with a local build of fabric8-generator

To run [fabric8-ui](https://github.com/fabric8io/fabric8-ui) against a locally running/debugging Fabric8 Generator in the fabric8-ui project type:
```
cd fabric8-ui
source environments/openshift-prod-cluster.sh
export FABRIC8_FORGE_API_URL=http://localhost:8080
npm start
```
then open [http://localhost:3000/](http://localhost:3000/) to use the local build of [fabric8-ui](https://github.com/fabric8io/fabric8-ui) which should now use your local Fabric8 Generator.

## Using a local build of the fabric8-generator addon

When working on the [fabric8-generator](https://github.com/fabric8io/fabric8-generator) codebase you need to (temporarily) change the [pom.xml](pom.xml) in this project to point to the `1.0.0-SNAPSHOT` version of `fabric8-generator`.

You can do that by changing [this line which defines the fabric8.generator.version property](https://github.com/fabric8io/generator-backend/blob/master/pom.xml#L22) to this:
```xml
      <fabric8.generator.version>1.0.0-SNAPSHOT</fabric8.generator.version>
```

Now run `./build.sh` in this project.

Next time you want to make a code change you can just rebuild `mvn install` in the [fabric8-generator](https://github.com/fabric8io/fabric8-generator) project and the `./run.sh` or `./debug.sh` will automatically reload the new version of your addon! This greatly speeds up development time!
