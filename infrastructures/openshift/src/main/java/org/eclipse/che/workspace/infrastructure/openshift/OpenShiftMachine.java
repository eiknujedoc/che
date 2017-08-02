/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.workspace.infrastructure.openshift;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.client.OpenShiftClient;
import okhttp3.Response;

import org.eclipse.che.api.core.model.workspace.runtime.Machine;
import org.eclipse.che.api.core.model.workspace.runtime.Server;
import org.eclipse.che.api.core.model.workspace.runtime.ServerStatus;
import org.eclipse.che.api.workspace.server.model.impl.ServerImpl;
import org.eclipse.che.api.workspace.server.spi.InfrastructureException;
import org.eclipse.che.api.workspace.server.spi.InternalInfrastructureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static org.eclipse.che.workspace.infrastructure.openshift.Constants.CHE_SERVER_NAME_ANNOTATION;
import static org.eclipse.che.workspace.infrastructure.openshift.Constants.CHE_SERVER_PATH_ANNOTATION;
import static org.eclipse.che.workspace.infrastructure.openshift.Constants.CHE_SERVER_PROTOCOL_ANNOTATION;

/**
 * @author Sergii Leshchenko
 */
public class OpenShiftMachine implements Machine {
    private static final Logger LOG = LoggerFactory.getLogger(OpenShiftMachine.class);

    private static final String OPENSHIFT_POD_STATUS_RUNNING = "Running";

    private final OpenShiftClientFactory clientFactory;
    private final String                 projectName;
    private final String                 podName;
    private final String                 containerName;

    public OpenShiftMachine(OpenShiftClientFactory clientFactory,
                            String projectName,
                            String podName,
                            String containerName) {
        this.clientFactory = clientFactory;
        this.projectName = projectName;
        this.podName = podName;
        this.containerName = containerName;
    }

    public String getName() {
        return podName + "/" + containerName;
    }

    @Override
    public Map<String, String> getProperties() {
        return emptyMap();
    }

    @Override
    public Map<String, ? extends Server> getServers() {
        //TODO https://github.com/eclipse/che/issues/5688
        try (OpenShiftClient client = clientFactory.create()) {
            Set<String> matchedServices = getMatchedServices().stream()
                                                              .map(s -> s.getMetadata().getName())
                                                              .collect(Collectors.toSet());

            List<Route> routes = client.routes()
                                       .inNamespace(projectName)
                                       .list()
                                       .getItems();

            Map<String, ServerImpl> servers = new HashMap<>();
            for (Route route : routes) {
                if (matchedServices.contains(route.getSpec().getTo().getName())) {
                    Map<String, String> annotations = route.getMetadata().getAnnotations();
                    String serverName = annotations.get(CHE_SERVER_NAME_ANNOTATION);
                    String serverPath = annotations.get(CHE_SERVER_PATH_ANNOTATION);
                    String serverProtocol = annotations.get(CHE_SERVER_PROTOCOL_ANNOTATION);
                    if (serverName != null) {
                        servers.put(serverName,
                                    //TODO Fix server status https://github.com/eclipse/che/issues/5689
                                    new ServerImpl(serverProtocol + "://" + route.getSpec().getHost() + serverPath, ServerStatus.RUNNING));
                    }
                }
            }
            return servers;
        }
    }

    private Pod getPod() {
        try (OpenShiftClient client = clientFactory.create()) {
            return client.pods()
                         .inNamespace(projectName)
                         .withName(podName)
                         .get();
        }
    }

    public void exec(String... command) throws InfrastructureException {
        ExecWatchdog watchdog = new ExecWatchdog();
        try (OpenShiftClient client = clientFactory.create();
             ExecWatch watch = client.pods()
                                     .inNamespace(projectName)
                                     .withName(podName)
                                     .inContainer(containerName)
                                     //TODO Investigate why redirection output and listener doesn't work together
                                     .usingListener(watchdog)
                                     .exec(encode(command))) {
            try {
                //TODO Make it configurable
                watchdog.wait(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InfrastructureException(e.getMessage(), e);
            }
        } catch (KubernetesClientException e) {
            throw new InfrastructureException(e.getMessage());
        }
    }

    public void waitRunning(int timeoutMin) throws InfrastructureException {
        LOG.info("Waiting machine {}", getName());

        CompletableFuture<Void> future = new CompletableFuture<>();
        Watch watch;
        try (OpenShiftClient client = clientFactory.create()) {
            Pod actualPod = client.pods()
                                  .inNamespace(projectName)
                                  .withName(podName)
                                  .get();

            if (actualPod == null) {
                throw new InternalInfrastructureException("Can't find created pod " + podName);
            }
            String status = actualPod.getStatus().getPhase();
            LOG.info("Machine {} is {}", getName(), status);
            if (OPENSHIFT_POD_STATUS_RUNNING.equals(status)) {
                future.complete(null);
                return;
            } else {
                watch = client.pods()
                              .inNamespace(projectName)
                              .withName(podName)
                              .watch(new Watcher<Pod>() {
                                         @Override
                                         public void eventReceived(Action action, Pod pod) {
                                             //TODO Replace with checking container status
                                             String phase = pod.getStatus().getPhase();
                                             LOG.info("Machine {} is {}", getName(), status);
                                             if (OPENSHIFT_POD_STATUS_RUNNING.equals(phase)) {
                                                 future.complete(null);
                                             }
                                         }

                                         @Override
                                         public void onClose(KubernetesClientException cause) {
                                             if (!future.isDone()) {
                                                 future.completeExceptionally(
                                                         new InfrastructureException("Machine watching is interrupted"));
                                             }
                                         }
                                     }
                              );
            }
        }

        try {
            future.get(timeoutMin, TimeUnit.MINUTES);
            watch.close();
        } catch (ExecutionException e) {
            throw new InfrastructureException(e.getCause().getMessage(), e);
        } catch (TimeoutException e) {
            throw new InfrastructureException("Starting of machine " + getName() + " reached timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("Starting of machine " + getName() + " was interrupted");
        }
    }

    private String[] encode(String[] toEncode) throws InfrastructureException {
        String[] encoded = new String[toEncode.length];
        for (int i = 0; i < toEncode.length; i++) {
            try {
                encoded[i] = URLEncoder.encode(toEncode[i], "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new InfrastructureException(e.getMessage(), e);
            }
        }
        return encoded;
    }

    private List<Service> getMatchedServices() {
        Pod pod = getPod();
        Container container = pod.getSpec()
                                 .getContainers()
                                 .stream()
                                 .filter(c -> containerName.equals(c.getName()))
                                 .findAny()
                                 .orElseThrow(
                                         () -> new IllegalStateException("Corresponding pod for OpenShift machine doesn't exist."));

        try (OpenShiftClient client = clientFactory.create()) {
            return client.services()
                         .inNamespace(projectName)
                         .list()
                         .getItems()
                         .stream()
                         .filter(service -> isExposedByService(pod, service))
                         .filter(service -> isExposedByService(container, service))
                         .collect(Collectors.toList());
        }
    }

    private static boolean isExposedByService(Pod pod, Service service) {
        Map<String, String> labels = pod.getMetadata().getLabels();
        Map<String, String> selectorLabels = service.getSpec().getSelector();
        if (labels == null) {
            return false;
        }
        for (Map.Entry<String, String> selectorLabelEntry : selectorLabels.entrySet()) {
            if (!selectorLabelEntry.getValue().equals(labels.get(selectorLabelEntry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isExposedByService(Container container, Service service) {
        for (ServicePort servicePort : service.getSpec().getPorts()) {
            IntOrString targetPort = servicePort.getTargetPort();
            if (targetPort.getIntVal() != null) {
                for (ContainerPort containerPort : container.getPorts()) {
                    if (targetPort.getIntVal().equals(containerPort.getContainerPort())) {
                        return true;
                    }
                }
            } else {
                for (ContainerPort containerPort : container.getPorts()) {
                    if (targetPort.getStrVal().equals(containerPort.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private class ExecWatchdog implements ExecListener {
        private final CountDownLatch latch;

        private ExecWatchdog() {
            this.latch = new CountDownLatch(1);
        }

        @Override
        public void onOpen(Response response) {
        }

        @Override
        public void onFailure(Throwable t, Response response) {
            latch.countDown();
        }

        @Override
        public void onClose(int code, String reason) {
            latch.countDown();
        }

        public void wait(long timeout, TimeUnit timeUnit) throws InterruptedException {
            latch.await(timeout, timeUnit);
        }
    }
}
