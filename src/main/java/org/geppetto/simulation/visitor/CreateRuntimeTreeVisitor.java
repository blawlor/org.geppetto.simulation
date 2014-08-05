/*******************************************************************************
 * The MIT License (MIT)
 *
 * Copyright (c) 2011, 2013 OpenWorm.
 * http://openworm.org
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the MIT License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/MIT
 *
 * Contributors:
 *     	OpenWorm - http://openworm.org/people.html
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE
 * USE OR OTHER DEALINGS IN THE SOFTWARE.
 *******************************************************************************/
package org.geppetto.simulation.visitor;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import org.geppetto.core.common.GeppettoErrorCodes;
import org.geppetto.core.common.GeppettoInitializationException;
import org.geppetto.core.model.IModel;
import org.geppetto.core.model.IModelInterpreter;
import org.geppetto.core.model.ModelInterpreterException;
import org.geppetto.core.model.runtime.ANode;
import org.geppetto.core.model.runtime.AspectNode;
import org.geppetto.core.model.runtime.EntityNode;
import org.geppetto.core.model.runtime.RuntimeTreeRoot;
import org.geppetto.core.model.simulation.Aspect;
import org.geppetto.core.model.simulation.Entity;
import org.geppetto.core.model.simulation.Model;
import org.geppetto.core.model.simulation.Simulator;
import org.geppetto.core.simulation.ISimulationCallbackListener;
import org.geppetto.core.simulator.ISimulator;
import org.geppetto.core.visualisation.model.Point;
import org.geppetto.simulation.SessionContext;

import com.massfords.humantask.BaseVisitor;
import com.massfords.humantask.TraversingVisitor;

/**
 * Visitor used for retrieving entities and aspects from simulation file. a
 * Entity and Aspect nodes are created, and used to create skeleton of run time tree.
 * 
 * @author  Jesus R. Martinez (jesus@metacell.us)
 *
 */
public class CreateRuntimeTreeVisitor extends TraversingVisitor{

	private SessionContext _sessionContext;
	private ISimulationCallbackListener _simulationCallback;
	
	public CreateRuntimeTreeVisitor(SessionContext sessionContext, ISimulationCallbackListener simulationCallback)
	{
		super(new DepthFirstTraverserEntitiesFirst(), new BaseVisitor());
		this._sessionContext = sessionContext;
		this._simulationCallback=simulationCallback;
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see com.massfords.humantask.TraversingVisitor#visit(org.geppetto.simulation.model.Aspect)
	 */
	@Override
	public void visit(Aspect aspect)
	{
		/*
		 * Extract information from aspect and create local aspect node
		 */
		Model model = aspect.getModel();
		Simulator simulator = aspect.getSimulator();
		AspectNode clientAspect = new AspectNode();
		clientAspect.setId(aspect.getId());
		clientAspect.setName(aspect.getId());
		
		//attach to parent entity before populating skeleton of aspect node
		addAspectToEntity(clientAspect, aspect.getParentEntity());

		if(model != null)
		{
			try
			{
				//use model interpreter from aspect to populate runtime tree
				IModelInterpreter modelInterpreter = _sessionContext.getModelInterpreter(model);
				modelInterpreter.populateRuntimeTree(clientAspect);
				
				IModel wrapper = modelInterpreter.readModel(new URL(model.getModelURL()), null, model.getParentAspect().getInstancePath());		
						
				clientAspect.setModel(wrapper);
				clientAspect.setModelInterpreter(modelInterpreter);
			}
			catch(GeppettoInitializationException e)
			{
				_simulationCallback.error(GeppettoErrorCodes.SIMULATION, this.getClass().getName(), null,e);
			}
			catch(MalformedURLException e)
			{
				_simulationCallback.error(GeppettoErrorCodes.SIMULATION, this.getClass().getName(), null,e);
			}
			catch(ModelInterpreterException e)
			{
				_simulationCallback.error(GeppettoErrorCodes.MODEL_INTERPRETER, this.getClass().getName(), null,e);
			}
		}
		
		/*
		 * Extract simulator from aspect and set it to client aspect node
		 */
		if(simulator != null)
		{
			try
			{
				ISimulator simulatorService = _sessionContext.getSimulator(simulator);				
				clientAspect.setSimulator(simulatorService);
			}
			catch(GeppettoInitializationException e)
			{
				_simulationCallback.error(GeppettoErrorCodes.SIMULATION, this.getClass().getName(), null,e);
			}
		}
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see com.massfords.humantask.TraversingVisitor#visit(org.geppetto.simulation.model.Aspect)
	 */
	@Override
	public void visit(Entity entity)
	{
		EntityNode clientEntity = new EntityNode();
		clientEntity.setName(entity.getId());
		clientEntity.setId(entity.getId());
		if(entity.getPosition()!=null){
			Point position = new Point();
			position.setX(new Double(entity.getPosition().getX()));
			position.setY(new Double(entity.getPosition().getY()));
			position.setZ(new Double(entity.getPosition().getZ()));
			clientEntity.setPosition(position);
		}
		getRuntimeModel().addChild(clientEntity);
		
		super.visit(entity);
	}

	/**
	 * Attaches AspectNode to its client parent EntityNode
	 * 
	 * @param aspectNode - Runtime Aspect Node
	 * @param entity - Persistent model Entity
	 */
	private void addAspectToEntity(AspectNode aspectNode, Entity entity){
		List<ANode> children = this.getRuntimeModel().getChildren();
		
		//traverse through runtimetree entities to find the parent of aspectNode
		for(int i =0; i<children.size(); i++){
			EntityNode currentEntity = ((EntityNode)children.get(i));
			if(currentEntity.getId().equals(entity.getId())){
				currentEntity.addChild(aspectNode);
			}
		}
	}
	
	public RuntimeTreeRoot getRuntimeModel(){
		return _sessionContext.getRuntimeTreeRoot();
	}
}