/**
 * Copyright 2005-2015 Red Hat, Inc.
 * <p>
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.obsidiantoaster.generator.rest;

import org.jboss.forge.addon.resource.Resource;
import org.jboss.forge.addon.resource.ResourceFactory;
import org.jboss.forge.addon.ui.command.CommandFactory;
import org.jboss.forge.addon.ui.command.UICommand;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UISelection;
import org.jboss.forge.addon.ui.controller.CommandController;
import org.jboss.forge.addon.ui.controller.CommandControllerFactory;
import org.jboss.forge.addon.ui.controller.WizardCommandController;
import org.jboss.forge.addon.ui.result.CompositeResult;
import org.jboss.forge.addon.ui.result.Failed;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.furnace.versions.Versions;
import org.jboss.forge.service.ui.RestUIContext;
import org.jboss.forge.service.ui.RestUIRuntime;
import org.jboss.forge.service.util.UICommandHelper;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;
import org.obsidiantoaster.generator.ForgeInitializer;
import org.obsidiantoaster.generator.event.FurnaceStartup;
import org.obsidiantoaster.generator.util.JsonBuilder;

import javax.enterprise.concurrent.ManagedExecutorService;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonStructure;
import javax.json.JsonWriter;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import static javax.json.Json.createArrayBuilder;
import static javax.json.Json.createObjectBuilder;

@javax.ws.rs.Path("/forge")
@ApplicationScoped
public class ObsidianResource
{
   public static final String CATAPULT_SERVICE_HOST = "CATAPULT_SERVICE_HOST";
   private static final String DEFAULT_COMMAND_NAME = "fabric8-new-project";
   private static final Logger log = Logger.getLogger(ObsidianResource.class.getName());
   private final Map<String, String> commandMap = new TreeMap<>();

   private final BlockingQueue<Path> directoriesToDelete = new LinkedBlockingQueue<>();

   @javax.annotation.Resource
   private ManagedExecutorService executorService;
   @Inject
   private CommandFactory commandFactory;
   @Inject
   private CommandControllerFactory controllerFactory;
   @Inject
   private ResourceFactory resourceFactory;
   @Inject
   private UICommandHelper helper;

   public ObsidianResource()
   {
      //commandMap.put("launchpad-new-project", "Launchpad: New Project");
      //commandMap.put("launchpad-new-starter-project", "Launchpad: New Starter Project");

      commandMap.put("fabric8-new-project", "Fabric8: New Project");
      //commandMap.put("fabric8-new-quickstart", "Fabric8: New Quickstart");

      commandMap.put("fabric8-import-git", "fabric8: Import Git");
      commandMap.put("fabric8-check-git-accounts", "fabric8: Check Git Accounts");

      // TODO only enable if not using SaaS mode:
      commandMap.put("fabric8-configure-git-account", "fabric8: Configure Git Account");
   }

   /**
    * Returns the entity from the result handling {@link CompositeResult} values as a List of entities
    */
   protected static Object getEntity(Result result)
   {
      if (result instanceof CompositeResult)
      {
         CompositeResult compositeResult = (CompositeResult) result;
         List<Object> answer = new ArrayList<>();
         List<Result> results = compositeResult.getResults();
         for (Result child : results)
         {
            Object entity = getEntity(child);
            answer.add(entity);
         }
         return answer;
      }
      return result.getEntity().orElse(null);
   }

   /**
    * Returns the result message handling composite results
    */
   protected static String getMessage(Result result)
   {
      if (result instanceof CompositeResult)
      {
         CompositeResult compositeResult = (CompositeResult) result;
         StringBuilder builder = new StringBuilder();
         List<Result> results = compositeResult.getResults();
         for (Result child : results)
         {
            String message = getMessage(child);
            if (message != null && message.trim().length() > 0)
            {
               if (builder.length() > 0)
               {
                  builder.append("\n");
               }
               builder.append(message);
            }
         }
         return builder.toString();

      }
      return result.getMessage();
   }

   void init(@Observes FurnaceStartup startup)
   {
      try
      {
         log.info("Warming up internal cache");
         // Warm up
         getCommand(DEFAULT_COMMAND_NAME, ForgeInitializer.getRoot(), null);
         log.info("Caches warmed up");
         executorService.submit(() ->
         {
            java.nio.file.Path path = null;
            try
            {
               while ((path = directoriesToDelete.take()) != null)
               {
                  log.info("Deleting " + path);
                  org.obsidiantoaster.generator.util.Paths.deleteDirectory(path);
               }
            }
            catch (IOException io)
            {
               log.log(Level.SEVERE, "Error while deleting" + path, io);
            }
            catch (InterruptedException e)
            {
               // Do nothing
            }
         });
      }
      catch (Exception e)
      {
         log.log(Level.SEVERE, "Error while warming up cache", e);
      }
   }

   @GET
   @javax.ws.rs.Path("/version")
   @Produces(MediaType.APPLICATION_JSON)
   public JsonObject getInfo()
   {
      return createObjectBuilder()
               .add("backendVersion", String.valueOf(ForgeInitializer.getVersion()))
               .add("forgeVersion", Versions.getImplementationVersionFor(UIContext.class).toString())
               .build();
   }

   @GET
   @javax.ws.rs.Path("/commands/{commandName}")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getCommandInfo(
            @PathParam("commandName") @DefaultValue(DEFAULT_COMMAND_NAME) String commandName,
            @Context HttpHeaders headers)
            throws Exception
   {
      try
      {
         validateCommand(commandName);
         JsonObjectBuilder builder = createObjectBuilder();
         try (CommandController controller = getCommand(commandName, ForgeInitializer.getRoot(), headers))
         {
            helper.describeController(builder, controller);
         }
         return Response.ok(builder.build()).build();
      }
      catch (Exception e)
      {
         return errorResponse(e);
      }
   }

   @POST
   @javax.ws.rs.Path("/commands/{commandName}/validate")
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   public Response validateCommand(JsonObject content,
            @PathParam("commandName") @DefaultValue(DEFAULT_COMMAND_NAME) String commandName,
            @Context HttpHeaders headers)
            throws Exception
   {
      try
      {
         validateCommand(commandName);
         JsonObjectBuilder builder = createObjectBuilder();
         try (CommandController controller = getCommand(commandName, ForgeInitializer.getRoot(), headers))
         {
            helper.populateControllerAllInputs(content, controller);
            helper.describeCurrentState(builder, controller);
            helper.describeValidation(builder, controller);
            helper.describeInputs(builder, controller);
         }
         return Response.ok(builder.build()).build();
      }
      catch (Exception e)
      {
         return errorResponse(e);
      }
   }

   @POST
   @javax.ws.rs.Path("/commands/{commandName}/next")
   @Consumes(MediaType.APPLICATION_JSON)
   @Produces(MediaType.APPLICATION_JSON)
   public Response nextStep(JsonObject content,
            @PathParam("commandName") @DefaultValue(DEFAULT_COMMAND_NAME) String commandName,
            @Context HttpHeaders headers)
            throws Exception
   {
      try
      {
         validateCommand(commandName);
         int stepIndex = content.getInt("stepIndex", 1);
         JsonObjectBuilder builder = createObjectBuilder();
         try (CommandController controller = getCommand(commandName, ForgeInitializer.getRoot(), headers))
         {
            if (!(controller instanceof WizardCommandController))
            {
               throw new WebApplicationException("Controller is not a wizard", Status.BAD_REQUEST);
            }
            WizardCommandController wizardController = (WizardCommandController) controller;
            for (int i = 0; i < stepIndex; i++)
            {
               helper.populateController(content, wizardController);
               if (wizardController.canMoveToNextStep())
               {
                  wizardController.next().initialize();
               }
               else
               {
                  helper.describeValidation(builder, controller);
                  break;
               }
            }
            helper.describeMetadata(builder, controller);
            helper.describeCurrentState(builder, controller);
            helper.describeInputs(builder, controller);
         }
         return Response.ok(builder.build()).build();
      }
      catch (Exception e)
      {
         return errorResponse(e);
      }
   }

   @GET
   @javax.ws.rs.Path("/commands/{commandName}/query")
   @Consumes(MediaType.MEDIA_TYPE_WILDCARD)
   @Produces({ MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN })
   public Response executeQuery(@Context UriInfo uriInfo,
            @PathParam("commandName") String commandName,
            @Context HttpHeaders headers)
            throws Exception
   {
      validateCommand(commandName);
      String stepIndex = null;
      MultivaluedMap<String, String> parameters = uriInfo.getQueryParameters();
      List<String> stepValues = parameters.get("stepIndex");
      if (stepValues != null && !stepValues.isEmpty())
      {
         stepIndex = stepValues.get(0);
      }
      if (stepIndex == null)
      {
         stepIndex = "0";
      }
      final JsonBuilder jsonBuilder = new JsonBuilder().createJson(Integer.valueOf(stepIndex));
      for (Map.Entry<String, List<String>> entry : parameters.entrySet())
      {
         String key = entry.getKey();
         if (!"stepIndex".equals(key))
         {
            jsonBuilder.addInput(key, entry.getValue());
         }
      }

      final Response response = executeCommandJson(jsonBuilder.build(), commandName, headers);
      if (response.getEntity() instanceof JsonObject)
      {
         JsonObject responseEntity = (JsonObject) response.getEntity();
         String error = ((JsonObject) responseEntity.getJsonArray("messages").get(0)).getString("description");
         return Response.status(Status.PRECONDITION_FAILED).entity(unwrapJsonObjects(error)).build();
      }
      return response;
   }

   @POST
   @javax.ws.rs.Path("/commands/{commandName}/execute")
   @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
   public Response executeCommand(Form form,
            @PathParam("commandName") @DefaultValue(DEFAULT_COMMAND_NAME) String commandName,
            @Context HttpHeaders headers)
            throws Exception
   {
      validateCommand(commandName);
      String stepIndex = null;
      List<String> stepValues = form.asMap().remove("stepIndex");
      if (stepValues != null && !stepValues.isEmpty())
      {
         stepIndex = stepValues.get(0);
      }
      if (stepIndex == null)
      {
         stepIndex = "0";
      }
      final JsonBuilder jsonBuilder = new JsonBuilder().createJson(Integer.valueOf(stepIndex));
      for (Map.Entry<String, List<String>> entry : form.asMap().entrySet())
      {
         jsonBuilder.addInput(entry.getKey(), entry.getValue());
      }

      final Response response = executeCommandJson(jsonBuilder.build(), commandName, headers);
      if (response.getEntity() instanceof JsonObject)
      {
         JsonObject responseEntity = (JsonObject) response.getEntity();
         String error = ((JsonObject) responseEntity.getJsonArray("messages").get(0)).getString("description");
         return Response.status(Status.PRECONDITION_FAILED).entity(unwrapJsonObjects(error)).build();
      }
      return response;
   }

   @POST
   @javax.ws.rs.Path("/commands/{commandName}/zip")
   @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
   public Response downloadZip(Form form,
            @PathParam("commandName") @DefaultValue(DEFAULT_COMMAND_NAME) String commandName,
            @Context HttpHeaders headers)
            throws Exception
   {
      validateCommand(commandName);
      String stepIndex = form.asMap().remove("stepIndex").get(0);
      final JsonBuilder jsonBuilder = new JsonBuilder().createJson(Integer.parseInt(stepIndex));
      for (Map.Entry<String, List<String>> entry : form.asMap().entrySet())
      {
         jsonBuilder.addInput(entry.getKey(), entry.getValue());
      }

      final Response response = downloadZip(jsonBuilder.build(), commandName, headers);
      if (response.getEntity() instanceof JsonObject)
      {
         JsonObject responseEntity = (JsonObject) response.getEntity();
         String error = ((JsonObject) responseEntity.getJsonArray("messages").get(0)).getString("description");
         return Response.status(Status.PRECONDITION_FAILED).entity(unwrapJsonObjects(error)).build();
      }
      return response;
   }

   @POST
   @javax.ws.rs.Path("/commands/{commandName}/zip")
   @Consumes(MediaType.APPLICATION_JSON)
   public Response downloadZip(JsonObject content,
            @PathParam("commandName") @DefaultValue(DEFAULT_COMMAND_NAME) String commandName,
            @Context HttpHeaders headers)
            throws Exception
   {
      validateCommand(commandName);
      java.nio.file.Path path = Files.createTempDirectory("projectDir");
      try (CommandController controller = getCommand(commandName, path, headers))
      {
         helper.populateControllerAllInputs(content, controller);
         if (controller.isValid())
         {
            Result result = controller.execute();
            if (result instanceof Failed)
            {
               return Response.status(Status.INTERNAL_SERVER_ERROR).entity(result.getMessage()).build();
            }
            else
            {
               UISelection<?> selection = controller.getContext().getSelection();
               java.nio.file.Path projectPath = Paths.get(selection.get().toString());
               String artifactId = findArtifactId(content);
               byte[] zipContents = org.obsidiantoaster.generator.util.Paths.zip(artifactId, projectPath);
               return Response
                        .ok(zipContents)
                        .type("application/zip")
                        .header("Content-Disposition", "attachment; filename=\"" + artifactId + ".zip\"")
                        .build();
            }
         }
         else
         {
            JsonObjectBuilder builder = createObjectBuilder();
            helper.describeValidation(builder, controller);
            return Response.status(Status.PRECONDITION_FAILED).entity(unwrapJsonObjects(builder.build())).build();
         }
      }
      finally
      {
         directoriesToDelete.offer(path);
      }
   }

   @POST
   @javax.ws.rs.Path("/commands/{commandName}/execute")
   @Consumes(MediaType.APPLICATION_JSON)
   public Response executeCommandJson(JsonObject content,
            @PathParam("commandName") @DefaultValue(DEFAULT_COMMAND_NAME) String commandName,
            @Context HttpHeaders headers)
            throws Exception
   {
      validateCommand(commandName);
      java.nio.file.Path path = Files.createTempDirectory("projectDir");
      try (CommandController controller = getCommand(commandName, path, headers))
      {
         populateControllerAllStepInputs(content, controller);
         if (controller.isValid())
         {
            Result result = controller.execute();
            if (result instanceof Failed)
            {
               JsonObjectBuilder builder = Json.createObjectBuilder();
               helper.describeResult(builder, result);
               return Response.status(Status.INTERNAL_SERVER_ERROR).entity(unwrapJsonObjects(builder)).build();
            }
            else
            {
               Object entity = getEntity(result);
               if (entity != null)
               {
                  entity = unwrapJsonObjects(entity);
                  return Response
                           .ok(entity)
                           .type(MediaType.APPLICATION_JSON)
                           .build();
               }
               else
               {
                  return Response
                           .ok(getMessage(result))
                           .type(MediaType.TEXT_PLAIN)
                           .build();
               }
            }
         }
         else
         {
            JsonObjectBuilder builder = createObjectBuilder();
            helper.describeValidation(builder, controller);
            return Response.status(Status.PRECONDITION_FAILED).entity(unwrapJsonObjects(builder.build())).build();
         }
      }
      catch (Exception e)
      {
         return errorResponse(e);
/*
         StringWriter writer = new StringWriter();
         PrintWriter ps = new PrintWriter(writer);
         boolean first = true;
         Throwable t = e;
         while (true)
         {
            String prefix = first ? "Error: " : "Caused by: ";
            first = false;
            ps.println(prefix + t.getMessage());
            t.printStackTrace(ps);
            ps.println();
            Throwable previous = t;
            t = t.getCause();
            if (t == null || t == previous || t.equals(previous))
            {
               break;
            }
         }
         ps.close();
         return Response.status(Status.INTERNAL_SERVER_ERROR).entity(writer.toString()).build();
*/
      }
   }

   /**
    * Whatever step we are currently on in the UI lets populate all wizard steps we can in case the UI passes
    * through default values for future steps
    */
   public void populateControllerAllStepInputs(JsonObject content, CommandController controller) throws Exception
   {
      helper.populateController(content, controller);
      if (controller instanceof WizardCommandController)
      {
         WizardCommandController wizardController = (WizardCommandController) controller;
         for (int i = 0; wizardController.canMoveToNextStep(); i++)
         {
            wizardController.next().initialize();
            helper.populateController(content, wizardController);
         }
      }
   }




   protected Object unwrapJsonObjects(Object entity)
   {
      if (entity instanceof JsonObjectBuilder)
      {
         JsonObjectBuilder jsonObjectBuilder = (JsonObjectBuilder) entity;
         entity = jsonObjectBuilder.build();
      }
      if (entity instanceof JsonStructure)
      {
         StringWriter buffer = new StringWriter();
         JsonWriter writer = Json.createWriter(buffer);
         writer.write((JsonStructure) entity);
         writer.close();
         return buffer.toString();
      }
      return entity;
   }

   @POST
   @javax.ws.rs.Path("/commands/{commandName}/catapult")
   @Consumes(MediaType.APPLICATION_JSON)
   public Response uploadZip(JsonObject content,
            @PathParam("commandName") @DefaultValue(DEFAULT_COMMAND_NAME) String commandName,
            @Context HttpHeaders headers)
            throws Exception
   {
      Response response = downloadZip(content, commandName, headers);
      if (response.getStatus() != 200)
      {
         return response;
      }

      String fileName = findArtifactId(content);
      byte[] zipContents = (byte[]) response.getEntity();

      Client client = ClientBuilder.newBuilder().build();
      WebTarget target = client.target(createCatapultUri());
      client.property("Content-Type", MediaType.MULTIPART_FORM_DATA);
      Invocation.Builder builder = target.request();

      MultipartFormDataOutput multipartFormDataOutput = new MultipartFormDataOutput();
      multipartFormDataOutput.addFormData("file", new ByteArrayInputStream(zipContents),
               MediaType.MULTIPART_FORM_DATA_TYPE, fileName + ".zip");

      Response postResponse = builder.post(Entity.entity(multipartFormDataOutput, MediaType.MULTIPART_FORM_DATA_TYPE));
      return Response.ok(postResponse.getLocation().toString()).build();
   }

   private URI createCatapultUri()
   {
      UriBuilder uri = UriBuilder.fromPath("/api/catapult/upload");
      String serviceHost = System.getenv(CATAPULT_SERVICE_HOST);
      if (serviceHost == null)
      {
         throw new WebApplicationException("'" + CATAPULT_SERVICE_HOST + "' environment variable must be set!");
      }
      uri.host(serviceHost);
      String port = System.getenv("CATAPULT_SERVICE_PORT");
      uri.port(port != null ? Integer.parseInt(port) : 80);
      uri.scheme("http");
      return uri.build();
   }

   protected void validateCommand(String commandName)
   {
      if (commandMap.get(commandName) == null)
      {
         String message = "No such command `" + commandName + "`. Supported commmands are '"
                  + String.join("', '", commandMap.keySet()) + "'";
         throw new WebApplicationException(message, Status.NOT_FOUND);
      }
   }

   private String findArtifactId(JsonObject content)
   {
      String artifactId = content.getJsonArray("inputs").stream()
               .filter(input -> "named".equals(((JsonObject) input).getString("name")))
               .map(input -> ((JsonString) ((JsonObject) input).get("value")).getString())
               .findFirst().orElse("demo");
      return artifactId;
   }

   private CommandController getCommand(String name, Path initialPath, HttpHeaders headers) throws Exception
   {
      RestUIContext context = createUIContext(initialPath, headers);
      UICommand command = commandFactory.getNewCommandByName(context, commandMap.get(name));
      CommandController controller = controllerFactory.createController(context,
               new RestUIRuntime(Collections.emptyList()), command);
      controller.initialize();
      return controller;
   }

   private RestUIContext createUIContext(Path initialPath, HttpHeaders headers)
   {
      Resource<?> selection = resourceFactory.create(initialPath.toFile());
      RestUIContext context = new RestUIContext(selection, Collections.emptyList());
      if (headers != null)
      {
         Map<Object, Object> attributeMap = context.getAttributeMap();
         MultivaluedMap<String, String> requestHeaders = headers.getRequestHeaders();
         requestHeaders.keySet().forEach(key -> attributeMap.put(key, headers.getRequestHeader(key)));
      }
      return context;
   }

   protected Response errorResponse(Throwable e)
   {
      log.log(Level.SEVERE, e.getMessage(), e);
      JsonObject result = exceptionToJson(e, 7);
      return Response.status(Status.INTERNAL_SERVER_ERROR).entity(result).build();
   }

   protected JsonObject exceptionToJson(Throwable e, int depth)
   {
      JsonArrayBuilder stackElements = createArrayBuilder();
      StackTraceElement[] stackTrace = e.getStackTrace();
      JsonObjectBuilder builder = createObjectBuilder()
               .add("type", e.getClass().getName());

      add(builder, "message", e.getMessage());
      add(builder, "localizedMessage", e.getLocalizedMessage());
      add(builder, "forgeVersion", Versions.getImplementationVersionFor(UIContext.class).toString());

      if (stackTrace != null)
      {
         for (StackTraceElement element : stackTrace)
         {
            stackElements.add(strackTraceElementToJson(element));
         }
         builder.add("stackTrace", stackElements);
      }

      if (depth > 0)
      {
         Throwable cause = e.getCause();
         if (cause != null && cause != e)
         {
            builder.add("cause", exceptionToJson(cause, depth - 1));
         }
      }
      if (e instanceof WebApplicationException)
      {
         WebApplicationException webApplicationException = (WebApplicationException) e;
         Response response = webApplicationException.getResponse();
         if (response != null)
         {
            builder.add("status", response.getStatus());
         }
      }
      return builder.build();
   }

   private JsonObjectBuilder strackTraceElementToJson(StackTraceElement element)
   {
      JsonObjectBuilder builder = createObjectBuilder().add("line", element.getLineNumber());
      add(builder, "class", element.getClassName());
      add(builder, "file", element.getFileName());
      add(builder, "method", element.getMethodName());
      return builder;
   }

   private void add(JsonObjectBuilder builder, String key, String value)
   {
      if (value != null)
      {
         builder.add(key, value);
      }
   }

}
