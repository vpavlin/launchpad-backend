package org.obsidiantoaster.generator.websocket;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.inject.Inject;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.OnClose;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import javax.ws.rs.core.UriBuilder;

import static org.obsidiantoaster.generator.rest.ObsidianResource.CATAPULT_SERVICE_HOST;
import static org.obsidiantoaster.generator.rest.ObsidianResource.CATAPULT_SERVICE_PORT;

/**
 * A web socket endpoint for status that 'just' relays to catapult.
 */
@ServerEndpoint(value = "/status/{uuid}")
public class StatusEndpointProxy {

    private static Map<UUID, Session> clientSessions = Collections.synchronizedMap(new WeakHashMap<>());
    private static Map<UUID, Session> serverSessions = Collections.synchronizedMap(new WeakHashMap<>());

    @OnOpen
    public void onOpen(Session session, @PathParam("uuid") String uuidPath) throws IOException, DeploymentException {
        UUID uuid = UUID.fromString(uuidPath);
        clientSessions.put(uuid, session);
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = createCatapultUri(uuidPath);
        StatusEndpointClient endpoint = new StatusEndpointClient(session);
        Session serverSession = container.connectToServer(endpoint, uri);
        serverSessions.put(uuid, serverSession);
    }

    @OnClose
    public void onClose(@PathParam("uuid") String uuidPath) throws IOException {
        UUID uuid = UUID.fromString(uuidPath);
        clientSessions.remove(uuid);
        serverSessions.remove(uuid).close();
    }

    private URI createCatapultUri(String uuid) {
        String serviceHost = System.getenv(CATAPULT_SERVICE_HOST);
        String port = System.getenv(CATAPULT_SERVICE_PORT);

        return UriBuilder.fromPath("/api/catapult/status")
                .path(uuid)
                .scheme("ws")
                .port(port != null ? Integer.parseInt(port) : 80)
                .host(serviceHost).build();
    }
}
