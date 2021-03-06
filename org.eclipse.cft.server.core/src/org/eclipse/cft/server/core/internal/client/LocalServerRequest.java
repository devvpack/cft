/*******************************************************************************
 * Copyright (c) 2012, 2016 Pivotal Software, Inc. and others
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution. 
 * 
 * The Eclipse Public License is available at 
 * 
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * and the Apache License v2.0 is available at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * You may elect to redistribute this code under either of these licenses.
 *  
 *  Contributors:
 *     Pivotal Software, Inc. - initial API and implementation
 ********************************************************************************/
package org.eclipse.cft.server.core.internal.client;

import org.cloudfoundry.client.lib.CloudFoundryOperations;
import org.eclipse.cft.server.core.internal.CloudErrorUtil;
import org.eclipse.cft.server.core.internal.CloudFoundryPlugin;
import org.eclipse.cft.server.core.internal.CloudFoundryServer;
import org.eclipse.cft.server.core.internal.Messages;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.osgi.util.NLS;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.internal.Server;

/**
 * Specialisation of the client request that updates the local Cloud Foundry
 * server instance during the operation of the client request. Generally, most
 * client calls should using the local server request, although in certain cases
 * where a local server instance is not yet available, the client calls should
 * instead user the {@link ClientRequest}
 * 
 * @param <T>
 */
@SuppressWarnings("restriction")
public abstract class LocalServerRequest<T> extends ClientRequest<T> {

	private String accessTokenErrorLabel;

	public LocalServerRequest(String label) {
		super(label);
	}

	@Override
	protected String getTokenAccessErrorLabel() {
		if (accessTokenErrorLabel == null) {
			String label = super.getRequestLabel();
			String serverName = null;
			try {
				CloudFoundryServer cloudServer = getCloudServer();
				if (cloudServer != null && cloudServer.getServer() != null) {
					serverName = NLS.bind(Messages.LocalServerRequest_SERVER_LABEL, cloudServer.getServer().getId());
				}
			}
			catch (Throwable e) {
				// Don't log. If the
				// server failed to resolve, the request itself
				// will fail and will log the error accordingly
			}
			if (serverName != null) {
				accessTokenErrorLabel = label + " - " + serverName; //$NON-NLS-1$
			}
			else {
				accessTokenErrorLabel = label;
			}

		}
		return accessTokenErrorLabel;
	}

	@Override
	public T runAndWait(CloudFoundryOperations client, SubMonitor monitor) throws CoreException {
		CloudFoundryServer cloudServer = getCloudServer();
		
		if (!cloudServer.isSso()) {
			// The username/password should not be null in non-sso scenario.
			if (cloudServer.getUsername() == null || cloudServer.getUsername().length() == 0
					|| cloudServer.getPassword() == null || cloudServer.getPassword().length() == 0) {
				CloudFoundryPlugin.getCallback().getCredentials(cloudServer);
			}
		} else {
			if(cloudServer.getToken() == null) {
				CloudFoundryPlugin.getCallback().ssoLoginUserPrompt(cloudServer);
			}
		}
		
		Server server = (Server) cloudServer.getServer();

		// Any Server request will require the server to be connected, so update
		// the server state
		if (server.getServerState() == IServer.STATE_STOPPED || server.getServerState() == IServer.STATE_STOPPING) {
			server.setServerState(IServer.STATE_STARTING);
		}

		try {
			T result = super.runAndWait(client, monitor);

			// No errors at this stage, therefore assume operation was completed
			// successfully, and update
			// server state accordingly
			if (server.getServerState() != IServer.STATE_STARTED) {
				server.setServerState(IServer.STATE_STARTED);
			}
			return result;

		}
		catch (CoreException ce) {
			// If the server state was starting and the error is related when
			// the operation was
			// attempted, but the operation failed
			// set the server state back to stopped.
			if (CloudErrorUtil.isConnectionError(ce) && server.getServerState() == IServer.STATE_STARTING) {
				server.setServerState(IServer.STATE_STOPPED);
			}
			// server.setServerPublishState(IServer.PUBLISH_STATE_NONE);
			throw ce;
		}

	}

	@Override
	protected CloudFoundryOperations getClient(IProgressMonitor monitor) throws CoreException {
		return getCloudServer().getBehaviour().getClient(monitor);
	}

	/**
	 * 
	 * @return non-null Cloud Foundry server instance. If it cannot be resolved,
	 * throw {@link CoreException}
	 * @throws CoreException if Cloud Foundry server cannot be resolved.
	 */
	abstract protected CloudFoundryServer getCloudServer() throws CoreException;
}
