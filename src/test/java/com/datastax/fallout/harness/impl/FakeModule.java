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
package com.datastax.fallout.harness.impl;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.google.auto.service.AutoService;

import com.datastax.fallout.harness.Module;
import com.datastax.fallout.harness.Operation;
import com.datastax.fallout.ops.Ensemble;
import com.datastax.fallout.ops.Product;
import com.datastax.fallout.ops.PropertyGroup;
import com.datastax.fallout.ops.Provider;

@AutoService(Module.class)
public class FakeModule extends Module
{
    private static final String NAME = "fake";
    protected static final String PREFIX = "test.module." + NAME + ".";

    public FakeModule()
    {
    }

    public FakeModule(RunToEndOfPhaseMethod runToEndOfPhaseMethod)
    {
        super(runToEndOfPhaseMethod);
    }

    @Override
    public String prefix()
    {
        return PREFIX;
    }

    @Override
    public String name()
    {
        return NAME;
    }

    @Override
    public String description()
    {
        return "Fake module";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<Class<? extends Provider>> getRequiredProviders()
    {
        return Collections.emptySet();
    }

    @Override
    public List<Product> getSupportedProducts()
    {
        return Product.everything();
    }

    @Override
    public void run(Ensemble ensemble, PropertyGroup properties)
    {
        emit(Operation.Type.invoke);
        emit(Operation.Type.ok);
    }
}
