/*
 * Copyright (c) 2014 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.cli.command;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;

import com.spotify.helios.client.HeliosClient;
import com.spotify.helios.common.descriptors.Job;
import com.spotify.helios.common.protocol.CreateJobResponse;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JobCreateCommandTest {

  private static final String JOB_NAME = "foo";
  private static final String JOB_ID = JOB_NAME + ":123";
  private static final String EXEC_HEALTH_CHECK = "touch /this";
  private static final List<String> SECURITY_OPT =
      Lists.newArrayList("label:user:dxia", "apparmor:foo");
  private static final String NETWORK_MODE = "host";

  private final Namespace options = mock(Namespace.class);
  private final HeliosClient client = mock(HeliosClient.class);
  private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(baos);

  private JobCreateCommand command;

  final CreateJobResponse okResponse =
      new CreateJobResponse(CreateJobResponse.Status.OK, Collections.<String>emptyList(), "12345");


  @Before
  public void setUp() {
    // use a real, dummy Subparser impl to avoid having to mock out every single call
    final ArgumentParser parser = ArgumentParsers.newArgumentParser("test");
    final Subparser subparser = parser.addSubparsers().addParser("create");

    command = new JobCreateCommand(subparser);

    when(client.createJob(jobWhoseNameIs(JOB_NAME))).thenReturn(Futures.immediateFuture(okResponse));
  }

  @Test
  public void testValidJobCreateCommand() throws Exception {
    when(options.getString("id")).thenReturn(JOB_ID);
    when(options.getString("image")).thenReturn("busybox:latest");
    when(options.getString("exec_check")).thenReturn(EXEC_HEALTH_CHECK);
    // For some reason the mocked options.getInt() returns 0 by default.
    // Explicitly return null to check that the value from the JSON file doesn't get overwritten.
    when(options.getInt("grace_period")).thenReturn(null);
    doReturn(new File("src/main/resources/job_config.json")).when(options).get("file");
    doReturn(SECURITY_OPT).when(options).getList("security_opt");
    when(options.getString("network_mode")).thenReturn(NETWORK_MODE);
    final int ret = command.run(options, client, out, false, null);

    assertEquals(0, ret);
    final String output = baos.toString();
    assertThat(output, containsString(
        "\"env\":{\"JVM_ARGS\":\"-Ddw.feature.randomFeatureFlagEnabled=true\"}"));
    assertThat(output, containsString("\"gracePeriod\":100"));
    assertThat(output, containsString(
        "\"healthCheck\":{\"type\":\"exec\",\"command\":[\"touch\",\"/this\"],\"type\":\"exec\"},"));
    assertThat(output, containsString("\"securityOpt\":[\"label:user:dxia\",\"apparmor:foo\"]"));
    assertThat(output, containsString("\"networkMode\":\"host\""));
  }

  @Test
  public void testJobCreateCommandFailsWithInvalidJobID() throws Exception {
    when(options.getString("id")).thenReturn(JOB_NAME);
    when(options.getString("image")).thenReturn("busybox:latest");
    final int ret = command.run(options, client, out, false, null);
    assertEquals(1, ret);
  }

  @Test
  public void testJobCreateCommandFailsWithInvalidPortProtocol() throws Exception {
    when(options.getString("id")).thenReturn(JOB_ID);
    when(options.getString("image")).thenReturn("busybox");
    doReturn(ImmutableList.of("dns=53:53/http")).when(options).getList("port");
    final int ret = command.run(options, client, out, true, null);

    assertEquals(1, ret);
    final String output = baos.toString();
    assertThat(output, containsString("\"status\":\"INVALID_JOB_DEFINITION\"}"));
    assertThat(output, containsString("\"errors\":[\"Invalid port mapping protocol: http\"]"));
  }

  @Test(expected=IllegalArgumentException.class)
  public void testJobCreateCommandFailsWithInvalidFilePath() throws Exception {
    when(options.getString("id")).thenReturn(JOB_ID);
    when(options.getString("image")).thenReturn("busybox:latest");
    doReturn(new File("non/existant/file")).when(options).get("file");
    command.run(options, client, out, false, null);
  }

  private static Job jobWhoseNameIs(final String name) {
    return argThat(new ArgumentMatcher<Job>() {
      @Override
      public boolean matches(Object argument) {
        if (argument instanceof Job) {
          final Job job = (Job) argument;
          if (job.getId().getName().equals(name)) {
            return true;
          }
        }
        return false;
      }
    });
  }

}