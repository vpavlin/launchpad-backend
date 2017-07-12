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


## Using a local fabric8-generator with MiniShift

Once you are running a local fabric8-generator you should be able to query it like this:

```
curl http://localhost:8080/api/version
```

How if you are [running fabric8 locally on MiniShift](https://github.com/fabric8io/fabric8-platform/blob/master/INSTALL.md) you just need to point the console at your locally running process.

So first find out your local IP address via something like
```
ifconfig |grep inet | grep 192
```
Its probably something like `192.168.X.Y`

First test you've got the right IP via:

```
curl http://192.168.X.Y:8080/api/version
```

If that works then try edit the `fabric8 ConfigMap` to point at your local forge:

```
oc project fabric8
oc edit cm fabric8
```

Then 

* disable (delete or comment out)the annotation `expose.service-key.config.fabric8.io/forge` in `metadata.annotations`
* edit the `forge.api.url` in the `data:` section to be `http://192.168.X.Y:8080`

You can test your edit worked by doing something like:

```
$ oc export cm | grep "forge.api.url:"
    forge.api.url: http://192.168.X.Y:8080
```

After you save change to the `ConfigMap` it should cause a redeploy of the fabric8 console. Then if you refresh your console you should be able to use the forge wizard against your locally running Forge process.

