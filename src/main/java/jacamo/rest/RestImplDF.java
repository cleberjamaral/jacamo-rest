package jacamo.rest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.zookeeper.CreateMode;
import org.glassfish.jersey.internal.inject.AbstractBinder;

import com.google.gson.Gson;

import jason.infra.centralised.BaseCentralisedMAS;

@Singleton
@Path("/services")
public class RestImplDF extends AbstractBinder {

    @Override
    protected void configure() {
        bind(new RestImplDF()).to(RestImplDF.class);
    }

    /**
     * Get MAS Directory Facilitator containing agents and services they provide
     * Following the format suggested in the second example of
     * https://opensource.adobe.com/Spry/samples/data_region/JSONDataSetSample.html
     * We are providing lists of maps
     * 
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     *         when ok JSON of the DF Sample output (jsonifiedDF):
     *         {"marcos":{"agent":"marcos","services":["vender(banana)","iamhere"]}}
     *         Testing platform: http://json.parser.online.fr/
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getDFJSON() {
        try {
            Gson gson = new Gson();

            // Using format Map<String, Set> as a common representation of ZK and
            // BaseCentralisedMAS
            Map<String, Set<String>> commonDF = getCommonDF();

            // Json of the DF
            Map<String,Object> jsonifiedDF = new HashMap<>();
            for (String s : commonDF.keySet()) {
                Map<String, Object> agent = new HashMap<>();
                agent.put("agent", s);
                Set<String> services = new HashSet<>();
                services.addAll(commonDF.get(s));
                agent.put("services", services);
                jsonifiedDF.put(s,agent);
            }

            return Response
                    .ok()
                    .entity(gson.toJson(jsonifiedDF))
                    .header("Access-Control-Allow-Origin", "*")
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(500, e.getMessage()).build();
        }

    }
    
    protected Map<String, Set<String>> getCommonDF() throws Exception {
        // Using format Map<String, Set> as a common representation of ZK and
        // BaseCentralisedMAS
        Map<String, Set<String>> commonDF;
        if (JCMRest.getZKHost() == null) {
            commonDF = BaseCentralisedMAS.getRunner().getDF();
        } else {
            commonDF = new HashMap<String, Set<String>>();

            for (String s : JCMRest.getZKClient().getChildren().forPath(JCMRest.JaCaMoZKDFNodeId)) {
                for (String a : JCMRest.getZKClient().getChildren().forPath(JCMRest.JaCaMoZKDFNodeId + "/" + s)) {
                    commonDF.computeIfAbsent(a, k -> new HashSet<>()).add(s);
                }
            }
        }
        return commonDF;

    }

    /**
     * Get some agent's services
     * 
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     *         ["vender(banana)","iamhere"]
     */

    @Path("/{agname}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getServices(@PathParam("agname") String agName) {
        try {
            return Response
                    .ok()
                    .entity(new Gson().toJson( getCommonDF().get(agName) ))
                    .header("Access-Control-Allow-Origin", "*")
                    .build();

        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(500, e.getMessage()).build();
        }
    }

    /**
     * Add some service for an agent
     * 
     * JSON is a Map like {"service":"help(drunks)" }
     * 
     */
    @Path("/{agname}")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response addService(@PathParam("agname") String agName, Map<String, Object> values) {
        try {
            String service = values.get("service").toString();
            if (service == null) {
                return Response
                        .status(500, "a service name have to be informed")
                        .build();
            }
            if (JCMRest.getZKHost() == null) {
                BaseCentralisedMAS.getRunner().dfRegister(agName, service);
            } else {            
                String type = values.getOrDefault("type", "no-type").toString();
                String node = JCMRest.JaCaMoZKDFNodeId+"/"+service+"/"+agName;
                if (JCMRest.getZKClient().checkExists().forPath(node) == null) {
                    JCMRest.getZKClient().create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(node, type.getBytes());
                } else {
                    JCMRest.getZKClient().setData().forPath(node, type.getBytes());
                }
            }
                        
            return Response
                    .ok()
                    .build();
            
        } catch (Exception e) {
            e.printStackTrace();
            return Response.status(500, e.getMessage()).build();
        }
    }
}
