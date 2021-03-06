/*
 * Copyright 2020 DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.fallout.service.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import com.datastax.driver.mapping.annotations.Table;
import com.datastax.fallout.util.Exceptions;

@Table(name = "deleted_test_runs")
public class DeletedTestRun extends TestRun
{
    public static DeletedTestRun fromTestRun(TestRun testRun)
    {
        return Exceptions.getUnchecked(() -> {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new Jdk8Module());
            byte[] json = mapper.writeValueAsBytes(testRun);
            return mapper.readValue(json, DeletedTestRun.class);
        });
    }
}
