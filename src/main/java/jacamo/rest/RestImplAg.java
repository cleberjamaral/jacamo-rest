package jacamo.rest;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

import javax.inject.Singleton;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.glassfish.jersey.internal.inject.AbstractBinder;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.w3c.dom.Document;

import com.google.common.reflect.ClassPath;
import com.google.common.reflect.ClassPath.ClassInfo;
import com.google.gson.Gson;

import cartago.ArtifactId;
import cartago.ArtifactInfo;
import cartago.CartagoException;
import cartago.CartagoService;
import cartago.WorkspaceId;
import jaca.CAgentArch;
import jason.ReceiverNotFoundException;
import jason.architecture.AgArch;
import jason.asSemantics.Agent;
import jason.asSemantics.CircumstanceListener;
import jason.asSemantics.IntendedMeans;
import jason.asSemantics.Intention;
import jason.asSemantics.Option;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.ASSyntax;
import jason.asSyntax.Literal;
import jason.asSyntax.Plan;
import jason.asSyntax.PlanBody;
import jason.asSyntax.PlanLibrary;
import jason.asSyntax.Trigger;
import jason.asSyntax.Trigger.TEType;
import jason.asSyntax.VarTerm;
import jason.asSyntax.parser.ParseException;
import jason.infra.centralised.BaseCentralisedMAS;
import jason.infra.centralised.CentralisedAgArch;
import jason.stdlib.print;

/**
 * Agent's REST compile class
 * 
 * @author Jomi Fred Hubner
 * @author Cleber Jorge Amaral
 *
 */
@Singleton
@Path("/agents")
public class RestImplAg extends AbstractBinder {

    Map<String, StringBuilder> agLog = new HashMap<>();
    TranslAg tAg = new TranslAg();
    Gson gson = new Gson();

    @Override
    protected void configure() {
        bind(new RestImplAg()).to(RestImplAg.class);
    }
    
    Executor executor = Executors.newFixedThreadPool(4);

    /**
     * Produces JSON containing the list of existing agents Example: ["ag1","ag2"]
     * 
     * @return HTTP 200 Response (ok status)
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAgentsJSON() {
        return Response.ok().entity(gson.toJson(tAg.getAgents())).header("Access-Control-Allow-Origin", "*").build();
    }

    /**
     * Return agent object by agent's name
     * 
     * @param agName name of the agent
     * @return Agent object
     */
    private Agent getAgent(String agName) {
        CentralisedAgArch cag = BaseCentralisedMAS.getRunner().getAg(agName);
        if (cag != null)
            return cag.getTS().getAg();
        else
            return null;
    }

    /**
     * Create an Agent. Produces PLAIN TEXT with HTTP response for this operation If
     * an ASL file with the given name exists, it will launch an agent with existing
     * code. Otherwise, creates an agent that will start say 'Hi'.
     * 
     * @param agName name of the agent to be created
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     */
    @Path("/{agentname}")
    @POST
    @Produces(MediaType.TEXT_PLAIN)
    public Response createNewAgent(@PathParam("agentname") String agName) {
        try {
            String name = BaseCentralisedMAS.getRunner().getRuntimeServices().createAgent(agName, null, null, null,
                    null, null, null);
            BaseCentralisedMAS.getRunner().getRuntimeServices().startAgent(name);
            // set some source for the agent
            Agent ag = getAgent(name);

            try {

                File f = new File("src/agt/" + agName + ".asl");
                if (!f.exists()) {
                    f.createNewFile();
                    FileOutputStream outputFile = new FileOutputStream(f, false);
                    StringBuilder stringBuilder = new StringBuilder();
                    stringBuilder.append("//Agent created automatically\n\n");
                    stringBuilder.append("!start.\n\n");
                    stringBuilder.append("+!start <- .print(\"Hi\").\n\n");
                    stringBuilder.append("{ include(\"$jacamoJar/templates/common-cartago.asl\") }\n");
                    stringBuilder.append("{ include(\"$jacamoJar/templates/common-moise.asl\") }\n");
                    stringBuilder.append(
                            "// uncomment the include below to have an agent compliant with its organisation\n");
                    stringBuilder.append("//{ include(\"$moiseJar/asl/org-obedient.asl\") }");
                    byte[] bytes = stringBuilder.toString().getBytes();
                    outputFile.write(bytes);
                    outputFile.close();
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
            ag.load(new FileInputStream("src/agt/" + agName + ".asl"), agName + ".asl");
            // ag.setASLSrc("no-inicial.asl");
            createAgLog(agName, ag);

            return Response.ok("Agent '" + name + "' has been created!").build();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Response.status(500).build();
    }

    /**
     * Kill an agent. Produces PLAIN TEXT with response for this operation.
     * 
     * @param agName agent's name to be killed
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     * @throws ReceiverNotFoundException
     */
    @Path("/{agentname}")
    @DELETE
    @Produces(MediaType.TEXT_PLAIN)
    public Response killAgent(@PathParam("agentname") String agName) throws ReceiverNotFoundException {
        try {
            boolean r = BaseCentralisedMAS.getRunner().getRuntimeServices().killAgent(agName, "web", 0);

            return Response.ok("Result of kill: " + r).build();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Response.status(500).build();
    }

    /**
     * Produces Agent's intentions statuses in JSON format. Example:
     * {"idle":true,"nbIntentions":1,"intentions":[{"size":1,"finished":false,"id":161,"suspended":false}]}
     * 
     * @param agName agent's name
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     */
    @Path("/{agentname}/status")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAgentStatusJSON(@PathParam("agentname") String agName) {
        try {
            Map<String, Object> props = getAgent(agName).getTS().getUserAgArch().getStatus();
            return Response.ok(gson.toJson(props)).build();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Response.status(500).build();
    }

    /**
     * Get agent information (namespaces, roles, missions and workspaces) in JSON
     * format
     * 
     * @param agName name of the agent
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     * 
     */
    @Path("/{agentname}")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAgentDetailsJSON(@PathParam("agentname") String agName) {
        try {
            return Response.ok(gson.toJson(tAg.getAgentDetails(agName))).build();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Response.status(500).build();
    }

    /**
     * Return XML of agent's mind content including belief base, intentions and
     * plans. See Jason's agInspection.xsl file for processing this data.
     * 
     * @param agName name of the agent
     * @return A XML Document
     * @deprecated Agent's mind in JSON format is provided in /{agentname}
     */
    @Path("/{agentname}/mind")
    @GET
    @Produces(MediaType.APPLICATION_XML)
    public Document getAgentMindXml(@PathParam("agentname") String agName) {
        try {
            Agent ag = getAgent(agName);
            if (ag != null)
                return ag.getAgState();
            else
                return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Return agent's Belief base in JSON format.
     * 
     * @param agName
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     */
    @Path("/{agentname}/mind/bb")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAgentBBJSON(@PathParam("agentname") String agName) {
        try {
            Agent ag = getAgent(agName);
            List<String> bbs = new ArrayList<>();
            for (Literal l : ag.getBB()) {
                bbs.add(l.toString());
            }

            return Response.ok(gson.toJson(bbs)).build();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Response.status(500).build();
    }

    //TODO: This method will turn deprecated after GET/{agent} returning plans
    /**
     * Return agent's plans in TEXT PLAIN format
     * 
     * @param agName
     * @param label
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     */
    @Path("/{agentname}/plans")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response getAgentPlansTxt(@PathParam("agentname") String agName,
            @DefaultValue("all") @QueryParam("label") String label) {
        StringWriter so = new StringWriter();
        try {
            Agent ag = getAgent(agName);
            if (ag != null) {
                PlanLibrary pl = ag.getPL();
                if (label.equals("all"))
                    so.append(pl.getAsTxt(false));
                else
                    so.append(pl.get(label).toASString());
            }

            return Response.ok(so.toString()).build();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Response
                .status(500, "Internal Server Error! Agent '" + agName + "' does not exist or cannot be observed.")
                .build();
    }

    /**
     * Upload new plans to an agent. Plan maintained only in memory.
     * 
     * @param agName              name of the agent
     * @param plans               plans to be uploaded
     * @param uploadedInputStream <need revision>
     * @param fileDetail          <need revision>
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     */
    @Path("/{agentname}/plans")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.TEXT_HTML)
    public Response loadPlans(@PathParam("agentname") String agName,
            @DefaultValue("") @FormDataParam("plans") String plans,
            @FormDataParam("file") InputStream uploadedInputStream,
            @FormDataParam("file") FormDataContentDisposition fileDetail) {
        try {
            Agent ag = getAgent(agName);
            if (ag != null) {
                ag.parseAS(new StringReader(plans), "RrestAPI");

                System.out.println("agName: " + agName);
                System.out.println("plans: " + plans);
                System.out.println("restAPI://" + fileDetail.getFileName());
                System.out.println("uis: " + uploadedInputStream);

                ag.load(uploadedInputStream, "restAPI://" + fileDetail.getFileName());
            }

            return Response.ok("ok, code uploaded for agent '" + agName + "'!").build();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Response.status(500).build();
    }

    /**
     * Get list of internal actions for an agent
     * 
     * @return List of internal actions
     */
    public void getIASuggestions(Map<String,String> cmds) {
        try {
            ClassPath classPath = ClassPath.from(print.class.getClassLoader());
            Set<ClassInfo> allClasses = classPath.getTopLevelClassesRecursive("jason.stdlib");

            allClasses.forEach(a -> {
                try {
                    Class<?> c = a.load();
                    if (c.isAnnotationPresent(jason.stdlib.Manual.class)) {
                        // add full predicate provided by @Manual
                        jason.stdlib.Manual annotation = (jason.stdlib.Manual) c
                                .getAnnotation(jason.stdlib.Manual.class);
                        cmds.put(annotation.literal(), annotation.hint().replaceAll("\"", "`").replaceAll("'", "`"));
					} else {
						// add just the functor of the internal action
						cmds.put("." + a.getSimpleName(), "");
					}

				} catch (Exception e) {
					e.printStackTrace();
				}
			});

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Return a TEXT PLAIN of available internal action, external actions and
	 * commands for the given agent Example:
	 * "['.desire','.drop_desire','.drop_all_desires']"
	 * 
	 * @param agName Name of the agent
	 * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
	 *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
	 */
	@Path("/{agentname}/code")
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public Response getCodeCompletionSuggestions(@PathParam("agentname") String agName) {
		Map<String,String> commands = new HashMap<>();
		try {
            // get internal actions
			getPlansSuggestions(agName, commands);
            // get internal actions
            getIASuggestions(commands);
            // get external actions
            getEASuggestions(agName, commands);

            Gson json = new Gson();
            Map<String,String> sortedCmds = new TreeMap<>(commands);
            return Response.ok(json.toJson(sortedCmds)).build();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Response.status(500, "Server Internal Error! Could not get code completion suggestions.").build();
    }

	private void getPlansSuggestions(String agName, Map<String, String> commands) {
		try {
			// get agent's plans
            Agent ag = getAgent(agName);
            if (ag != null) {
                PlanLibrary pl = ag.getPL();
                for (Plan plan : pl.getPlans()) {
                    
                    // do not add plans that comes from jar files (usually system's plans)
                    if (plan.getSource().startsWith("jar:file") || plan.getSource().equals("kqmlPlans.asl"))
                        continue;

                    // add namespace when it is not default
                    String ns = "";
                    if (!plan.getNS().equals(Literal.DefaultNS)) {
                        ns = plan.getNS().toString() + "::";
                    }

                    String terms = "";
                    if (plan.getTrigger().getLiteral().getArity() > 0) {
                        for (int i = 0; i < plan.getTrigger().getLiteral().getArity(); i++) {
                            if (i == 0)
                                terms = "(";
                            terms += plan.getTrigger().getLiteral().getTerm(i).toString();
                            if (i < plan.getTrigger().getLiteral().getArity() - 1)
                                terms += ", ";
                            else
                                terms += ")";
                        }
                    }

                    // when it is a goal or test goal, do not add operator
                    if ((plan.getTrigger().getType() == TEType.achieve)
                            || (plan.getTrigger().getType() == TEType.test)) {


                        commands.put(ns + plan.getTrigger().getType().toString()
                        + plan.getTrigger().getLiteral().getFunctor() + terms, "");

                    }
                    // when it is belief, do not add type which is anyway empty
                    else if (plan.getTrigger().getType() == TEType.belief) {
                        commands.put(ns + plan.getTrigger().getOperator().toString()
                                + plan.getTrigger().getLiteral().getFunctor() + terms, "");
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void getEASuggestions(String agName, Map<String, String> commands) throws CartagoException {

        try {
            Agent ag = getAgent(agName);
            // get external actions (from focused artifacts)
            CAgentArch cartagoAgArch = getCartagoArch(ag);
            for (WorkspaceId wid : cartagoAgArch.getSession().getJoinedWorkspaces()) {
                String wksName = wid.getName();
                for (ArtifactId aid : CartagoService.getController(wksName).getCurrentArtifacts()) {

                    // operations
                    ArtifactInfo info = CartagoService.getController(wksName).getArtifactInfo(aid.getName());

                    info.getObservers().forEach(y -> {
                        if (y.getAgentId().getAgentName().equals(agName)) {
                            info.getOperations().forEach(z -> {
                                String params = "";
                                for (int i = 0; i < z.getOp().getNumParameters(); i++) {
                                    if (i == 0) params = "(";
                                    params += "arg" + i;
                                    if (i == z.getOp().getNumParameters() - 1)
                                        params += ")";
                                    else
                                        params += ", ";
                                }

                                commands.put(z.getOp().getName() + params, "");
                            });
                        }
                    });

                }
            }
        } catch (CartagoException e) {
            e.printStackTrace();
        }
    }

    /**
     * Send a command to an agent. Produces a TEXT PLAIN output containing a status
     * message
     * 
     * @param cmd    command expression
     * @param agName agent name
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     */
    @Path("/{agentname}/cmd")
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response runCmdPost(@FormParam("c") String cmd, @PathParam("agentname") String agName) {
        Agent ag = getAgent(agName);
        if (ag == null) {
            return Response.status(500, "Receiver '" + agName + "' not found").build();
        }
        try {
            createAgLog(agName, ag);
            
            cmd = cmd.trim();
            if (cmd.endsWith(".")) cmd = cmd.substring(0, cmd.length() - 1);

            Unifier u = execCmd(ag, ASSyntax.parsePlanBody(cmd));
            addAgLog(agName, "Command " + cmd + ": " + u);

            Map<String,String> um = new HashMap<String, String>();
            for (VarTerm v: u) {
                um.put(v.toString(), u.get(v).toString());
            }
            return Response.ok(gson.toJson(um)).build();
        } catch (ParseException e) {
            return Response.status(500, "Error parsing '" + cmd + "."+e.getMessage()).build();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Response.status(500).build();
    }

    /**
     * Get agent full log in a TEXT PLAIN format
     * 
     * @param agName agent name
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     */
    @Path("/{agentname}/log")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response getLogOutput(@PathParam("agentname") String agName) {
        try {
            StringBuilder o = agLog.get(agName);
            if (o != null) {
                return Response.ok(o.toString()).build();
            }
            return Response.ok().build();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Response.status(500).build();
    }

    /**
     * Delete agent's log.
     * 
     * @param agName
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     * 
     */
    @Path("/{agentname}/log")
    @DELETE
    @Produces(MediaType.TEXT_PLAIN)
    public Response delLogOutput(@PathParam("agentname") String agName) {
        try {
            agLog.put(agName, new StringBuilder());

            return Response.ok().build();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return Response.status(500).build();
    }

    /**
     * Send a command to an agent
     * 
     * @param agName name of the agent
     * @param sCmd   command to be executed
     * @return Status message
     * @throws ParseException 
     */
    Unifier execCmd(Agent ag, PlanBody lCmd) throws ParseException {
        Trigger te = ASSyntax.parseTrigger("+!run_repl_expr");
        Intention i = new Intention();
        IntendedMeans im = new IntendedMeans(new Option(new Plan(null, te, null, lCmd), new Unifier()), te);
        i.push(im);

        Lock lock = new ReentrantLock();
        Condition goalFinished  = lock.newCondition();
        executor.execute( () -> {
                /*GoalListener gl = new GoalListener() {                 
                    public void goalSuspended(Trigger goal, String reason) {}
                    public void goalStarted(Event goal) {}
                    public void goalResumed(Trigger goal) {}
                    public void goalFinished(Trigger goal, FinishStates result) {
                        System.out.println("finished!");
                        if (goal.equals(te)) {
                            // finished
                            //if (result.equals(FinishStates.achieved)) {
                            //}                           
                            try {
                                lock.lock();
                                goalFinished.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }                       
                    }
                    public void goalFailed(Trigger goal) {
                        if (goal.equals(te)) {
                            try {
                                lock.lock();
                                goalFinished.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }                                                   
                    }
                };*/
                CircumstanceListener cl = new CircumstanceListener() {
                    public void intentionDropped(Intention ci) {
                        System.out.println("*finished!"+ci);
                        if (ci.equals(i)) {
                            try {
                                lock.lock();
                                goalFinished.signalAll();
                            } finally {
                                lock.unlock();
                            }
                        }
                    };

                };
                TransitionSystem ts = ag.getTS();
                try {
                    lock.lock();
                    //ts.addGoalListener(gl);
                    ts.getC().addEventListener(cl);
                    ts.getC().addRunningIntention(i);
                    ts.getUserAgArch().wake();
                    goalFinished.await();
                    //ts.removeGoalListener(gl);
                    ts.getC().removeEventListener(cl);
                } catch (InterruptedException e) {                          
                } finally {
                    lock.unlock();
                }
                System.out.println("fim thread");
        });
        try {
            lock.lock();
            goalFinished.await();
            
            return im.getUnif();
        } catch (InterruptedException e) {
        } finally {
            lock.unlock();
        }
        return null;
    }

    /**
     * Creates a log area for an agent
     * 
     * @param agName agent name
     * @param ag     agent object
     */
    protected void createAgLog(String agName, Agent ag) {
        // adds a log for the agent
        if (agLog.get(agName) == null) {
            agLog.put(agName, new StringBuilder());
            ag.getTS().getLogger().addHandler(new StreamHandler() {
                @Override
                public void publish(LogRecord l) {
                    addAgLog(agName, l.getMessage());
                }
            });
        }
    }

    /**
     * Add a message to the agent log.
     * 
     * @param agName agent name
     * @param msg    message to be added
     */
    protected void addAgLog(String agName, String msg) {
        StringBuilder o = agLog.get(agName);
        if (o == null) {
            o = new StringBuilder();
            agLog.put(agName, o);
        } else {
            o.append("\n");
        }
        String dt = new SimpleDateFormat("dd-MM-yy HH:mm:ss").format(new Date());
        o.append("[" + dt + "] " + msg);
    }

    /**
     * Send a message to an agent. Consumes an XML containing the message.
     * 
     * @param m      Message
     * @param agName Agent name
     * @return HTTP 200 Response (ok status) or 500 Internal Server Error in case of
     *         error (based on https://tools.ietf.org/html/rfc7231#section-6.6.1)
     */
    @Path("/{agentname}/mb")
    @POST
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.TEXT_PLAIN)
    public Response addAgMsg(Message m, @PathParam("agentname") String agName) {
        try {
            CentralisedAgArch a = BaseCentralisedMAS.getRunner().getAg(agName);
            if (a != null) {
                a.receiveMsg(m.getAsJasonMsg());
                return Response.ok().build();
            } else {
                return Response.status(500, "Internal Server Error! Receiver '" + agName + "' not found").build();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Response.status(500).build();
    }

    @Path("/{agentname}/mb")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response addAgMsgJson(Message m, @PathParam("agentname") String agName) {
        try {
            CentralisedAgArch a = BaseCentralisedMAS.getRunner().getAg(agName);
            if (a != null) {
                a.receiveMsg(m.getAsJasonMsg());
                return Response.ok().build();
            } else {
                return Response.status(500, "Internal Server Error! Receiver '" + agName + "' not found").build();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return Response.status(500).build();
    }

    /**
     * Get agent's CArtAgO architecture
     * 
     * @param ag Agent object
     * @return agent's CArtAgO architecture
     */
    protected CAgentArch getCartagoArch(Agent ag) {
        AgArch arch = ag.getTS().getUserAgArch().getFirstAgArch();
        while (arch != null) {
            if (arch instanceof CAgentArch) {
                return (CAgentArch) arch;
            }
            arch = arch.getNextAgArch();
        }
        return null;
    }
}
