/*******************************************************************************
 * Copyright (c) 2016, 2019 Contributors to the Eclipse Foundation
 * 
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * Additional configuration for Kuksa: Expleo Germany GmbH
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.kuksa.testing.client;

import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import org.eclipse.hono.client.CommandClient;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.util.BufferResult;
import org.eclipse.kuksa.testing.config.HonoConfiguration;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * A command line client for sending commands to devices connected
 * to one of Hono's protocol adapters.
 * <p>
 * The commands to send are read from stdin.
 */
@Component
public class Commander extends AbstractCliClient {

    private WorkerExecutor workerExecutor;

    /**
     * Starts this component.
     *
     */
    public void start() {
        final Vertx vertx = HonoConfiguration.vertx();
        workerExecutor = vertx.createSharedWorkerExecutor("user-input-pool", 3, TimeUnit.HOURS.toNanos(1));

        clientFactory.connect().setHandler(connectAttempt -> {

            if (connectAttempt.succeeded()) {
                clientFactory.addReconnectListener(this::startCommandClient);
                startCommandClient(connectAttempt.result());
            } else {
                close(connectAttempt.cause());
            }
        });
    }

    private void startCommandClient(final HonoConnection connection) {
        sendPeriodicCommand()
        .compose(this::processCommand)
        .setHandler(sendAttempt -> startCommandClient(connection));
    }

    private Future<Void> processCommand(final Command command) {

        LOG.info("Command sent to device... [request will timeout in {} seconds]", 10);

        final Future<CommandClient> commandClient = clientFactory.getOrCreateCommandClient("DEFAULT_TENANT", "4711");
        return commandClient
                .map(this::setRequestTimeOut)
                .compose(c -> {
                    if (command.isOneWay()) {
                        return c
                                .sendOneWayCommand(command.getName(), command.getContentType(), Buffer.buffer(command.getPayload()), null)
                                .map(ok -> c);
                    } else {
                        return c
                                .sendCommand(command.getName(), command.getContentType(), Buffer.buffer(command.getPayload()), null)
                                .map(this::printResponse)
                                .map(ok -> c);
                    }
                })
                .map(this::closeCommandClient)
                .otherwise(error -> {
                    LOG.error("Error sending command: {}", error.getMessage());
                    if (commandClient.succeeded()) {
                        return closeCommandClient(commandClient.result());
                    } else {
                        return null;
                    }
                });
    }

    private CommandClient setRequestTimeOut(final CommandClient commandClient) {
        commandClient.setRequestTimeout(TimeUnit.SECONDS.toMillis(5));
        return commandClient;
    }

    private Void closeCommandClient(final CommandClient commandClient) {
        LOG.trace("Close command connection to device [{}:{}]", "DEFAULT_TENANT", "4711");
        commandClient.close(closeHandler -> {
        });
        return null;
    }

    private Void printResponse(final BufferResult result) {
        LOG.info("Received Command response : {}",
                Optional.ofNullable(result.getPayload()).orElse(Buffer.buffer()).toString());
        return null;
    }

    private Future<Command> sendPeriodicCommand() {
        final Future<Command> commandFuture = Future.future();
        workerExecutor.executeBlocking(userInputFuture -> {
                    userInputFuture.complete(new Command("MQTTTestCommand", "{\"temperature\":21}", "application/json"));
                },
                commandFuture);
        return commandFuture;
    }

    private void close(final Throwable t) {
        workerExecutor.close();
        vertx.close();
        LOG.error("Error: {}", t.getMessage());
    }

    public void close() {
        workerExecutor.close();
        vertx.close();
    }

    /**
     * Command class that encapsulates hono command and payload.
     */
    private static class Command {

        private final String name;
        private final String payload;
        private final String contentType;
        private final boolean oneWay;

        Command(final String command, final String payload, final String contentType) {

            oneWay = command.startsWith("ow:");
            if (oneWay) {
                name = command.substring(3);
            } else {
                name = command;
            }
            this.payload = payload;
            this.contentType = contentType;
        }

        private boolean isOneWay() {
            return oneWay;
        }

        private String getName() {
            return name;
        }

        private String getPayload() {
            return payload;
        }

        private String getContentType() {
            return contentType;
        }
    }
}
