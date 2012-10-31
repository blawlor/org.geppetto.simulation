package org.openworm.simulationengine.simulation;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.apache.catalina.websocket.MessageInbound;
import org.apache.catalina.websocket.StreamInbound;
import org.apache.catalina.websocket.WebSocketServlet;
import org.apache.catalina.websocket.WsOutbound;
import org.openworm.simulationengine.core.model.IModel;
import org.openworm.simulationengine.core.model.IModelInterpreter;
import org.openworm.simulationengine.core.simulator.ISimulator;
import org.openworm.simulationengine.core.visualisation.model.Scene;
import org.openworm.simulationengine.simulation.model.Aspect;
import org.openworm.simulationengine.simulation.model.Simulation;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configurable
@SuppressWarnings("serial")
public class SimulationServlet extends WebSocketServlet
{

	BundleContext _bc = FrameworkUtil.getBundle(this.getClass()).getBundleContext();

	// TODO: inject this from a config file local to this bundle
	private URL _configUrl;
	private static final long UPDATE_CYCLE = 100;
	private final Timer _simTimer = new Timer(SimulationServlet.class.getSimpleName() + " Timer");
	private final AtomicInteger _connectionIds = new AtomicInteger(0);
	private final SessionContext _sessionContext = new SessionContext();
	private final ConcurrentHashMap<Integer, SimDataInbound> _connections = new ConcurrentHashMap<Integer, SimDataInbound>();

	@Override
	protected StreamInbound createWebSocketInbound(String subProtocol, HttpServletRequest request)
	{
		return new SimDataInbound(_connectionIds.incrementAndGet());
	}

	@Override
	public void init() throws ServletException
	{
		super.init();
		SpringBeanAutowiringSupport.processInjectionBasedOnCurrentContext(this);
		_simTimer.scheduleAtFixedRate(new TimerTask()
		{
			@Override
			public void run()
			{
				try
				{
					update();
				}
				catch (JsonProcessingException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}, UPDATE_CYCLE, UPDATE_CYCLE);

		// TODO: this is hardcoded here temp - should be set from the client or
		// at least inject from config
		try
		{
			_configUrl = new File("./src/main/resources/config/sph-sim-config.xml").toURI().toURL();
		}
		catch (MalformedURLException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void update() throws JsonProcessingException
	{
		StringBuilder sb = new StringBuilder();

		for (String aspectID : _sessionContext.aspectIDs)
		{
			// TODO: how do we allow for multiple timesteps to be returned?
			
			// get models Map for the given aspect String = modelId / List<IModel> = a given model at different timesteps
			HashMap<String, List<IModel>> modelsMap = _sessionContext.modelsByAspect.get(aspectID);
			
			List<IModel> models = new ArrayList<IModel>();
			// traverse models
			for(String modelId : modelsMap.keySet())
			{
				if(modelsMap.get(modelId).size() > 0)
				{
					// get oldest and add it to the models list to be sent to the client
					models.add(modelsMap.get(modelId).get(0));
					// remove oldest from the original buffer
					modelsMap.get(modelId).remove(0);
				}
			}		
			
			// create scene
			Scene scene = _sessionContext.modelInterpretersByAspect.get(aspectID).getSceneFromModel(models);
			ObjectMapper mapper = new ObjectMapper();
			mapper.writer().writeValueAsString(scene);

			// TODO: figure out how to separate aspects in the representation
		}

		sendUpdate(sb.toString());
	}

	private void sendUpdate(String message)
	{
		for (SimDataInbound connection : getConnections())
		{
			try
			{
				CharBuffer buffer = CharBuffer.wrap(message);
				connection.getWsOutbound().writeTextMessage(buffer);
			}
			catch (IOException ignore)
			{
				// Ignore
			}
		}
	}

	private Collection<SimDataInbound> getConnections()
	{
		return Collections.unmodifiableCollection(_connections.values());
	}

	private final class SimDataInbound extends MessageInbound
	{

		private final int id;

		private SimDataInbound(int id)
		{
			super();
			this.id = id;
		}

		@Override
		protected void onOpen(WsOutbound outbound)
		{
			_connections.put(Integer.valueOf(id), this);
		}

		@Override
		protected void onClose(int status)
		{
			_connections.remove(Integer.valueOf(id));
		}

		@Override
		protected void onBinaryMessage(ByteBuffer message) throws IOException
		{
			throw new UnsupportedOperationException("Binary message not supported.");
		}

		@Override
		protected void onTextMessage(CharBuffer message)
		{
			try
			{
				String msg = message.toString();
				if (msg.equals("start"))
				{
					Simulation sim = SimulationConfigReader.readConfig(_configUrl);

					// grab config and retrieve model interpreters and
					// simulators
					populateDiscoverableServices(sim);

					// start simulation thread
					new SimulationThread(_sessionContext).start();
				}
				else if (msg.equals("stop"))
				{
					_sessionContext.runSimulation = false;
					_sessionContext.runningCycle = false;
				}
				else
				{
					// NOTE: doesn't necessarily need to do smt here - could be
					// just start/stop
				}
			}
			catch (InvalidSyntaxException e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	private void populateDiscoverableServices(Simulation simConfig) throws InvalidSyntaxException
	{
		for (Aspect aspect : simConfig.getAspects())
		{
			String id = aspect.getId();
			String modelInterpreterId = aspect.getModelInterpreter();
			String simulatorId = aspect.getSimulator();
			String modelURL = aspect.getModelURL();

			IModelInterpreter modelInterpreter = this.<IModelInterpreter> getService(modelInterpreterId, IModelInterpreter.class.getName());
			ISimulator simulator = this.<ISimulator> getService(simulatorId, ISimulator.class.getName());

			_sessionContext.aspectIDs.add(id);
			_sessionContext.modelInterpretersByAspect.put(id, modelInterpreter);
			_sessionContext.simulatorsByAspect.put(id, simulator);
			_sessionContext.modelURLByAspect.put(id, modelURL);
		}
	}

	/*
	 * A generic routine to encapsulate boiler-plate code for dynamic service
	 * discovery
	 */
	private <T> T getService(String discoveryId, String type) throws InvalidSyntaxException
	{
		T service = null;

		String filter = String.format("(discoverableID=%s)", discoveryId);
		ServiceReference[] sr = _bc.getServiceReferences(type, filter);
		if (sr != null && sr.length > 0)
		{
			service = (T) _bc.getService(sr[0]);
		}

		return service;
	}
}
