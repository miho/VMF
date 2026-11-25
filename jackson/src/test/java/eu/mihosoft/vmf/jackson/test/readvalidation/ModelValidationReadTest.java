/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Proceedings of the 2013 ACM workshop on Domain-Specific Modeling - DSM '13.
 * doi:10.1145/2541928.2541935
 */
package eu.mihosoft.vmf.jackson.test.readvalidation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.jackson.VMFValidationException;
import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelValidationReadTest {
    private static final VMFJacksonModule.RUNTIME_TYPE MODE = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;

    @Test
    void validatesTheOutermostModelOnceAndRetainsStructuredIssues() throws Exception {
        var calls = new AtomicInteger();
        var validator = ModelValidator.builder().withGraphRule((root, context) -> calls.incrementAndGet()).build();
        var defaultMapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
        var validatingMapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE)
                .withModelValidator(validator));

        String invalid = "{\"instruments\":[{\"setpoint\":11}]}";
        var loaded = defaultMapper.readValue(invalid, InstrumentGroup.class);
        assertEquals(11.0, loaded.getInstruments().get(0).getSetpoint());
        var declared = loaded.vmf().content().validate();
        assertFalse(declared.isValid());
        assertEquals("vmf.range", declared.getIssues().get(0).getCode());
        var error = assertThrows(VMFValidationException.class, () -> validatingMapper.readValue(invalid, InstrumentGroup.class));
        var issues = error.getReport().getIssues();
        assertEquals(1, issues.size());
        assertEquals("/instruments/0/setpoint", issues.get(0).getPath());
        assertEquals("vmf.range", issues.get(0).getCode());
        assertEquals(1, calls.get());

        var valid = validatingMapper.readValue("{\"instruments\":[{\"setpoint\":2}]}", InstrumentGroup.class);
        assertSame(valid, valid.getInstruments().get(0).getGroup());
        assertEquals(2, calls.get());
    }

    @Test
    void resolvesReferencesBeforeInvokingApplicationRules() throws Exception {
        var calls = new AtomicInteger();
        var validator = ModelValidator.builder().withRule(InstrumentGroup.class, (group, context) -> {
            assertSame(group.getInstruments().get(0), group.getPrimary());
            calls.incrementAndGet();
        }).build();
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE)
                .withReferences(true).withModelValidator(validator));

        String input = "{\"instruments\":[{\"@vmf-id\":\"1\",\"setpoint\":5}],\"primary\":\"1\"}";
        var group = mapper.readValue(input, InstrumentGroup.class);
        assertSame(group.getInstruments().get(0), group.getPrimary());
        assertEquals(1, calls.get());
    }

    @Test
    void customAlarmPolicyRejectsTheSameNestedInputInTheModelAndWireSchema() throws Exception {
        String invalid = "{\"instruments\":[{\"setpoint\":5,\"alarm-enabled\":true}]}";
        String repaired = "{\"instruments\":[{\"setpoint\":5,\"alarm-enabled\":true,\"alarm-limit\":8}]}";
        String inactive = "{\"instruments\":[{\"setpoint\":5}]}";

        var rule = ModelValidator.builder().withRule(Instrument.class, (instrument, context) -> {
            if (instrument.isAlarmEnabled() && instrument.getAlarmLimit() == null) {
                context.report(ValidationIssue.Severity.ERROR, "instrument.alarm-limit.required", "alarmLimit",
                        "An enabled alarm must have a limit");
            }
        }).build();
        var condition = Map.of(
                "if", Map.of("required", List.of("alarm-enabled"),
                        "properties", Map.of("alarm-enabled", Map.of("const", true))),
                "then", Map.of("required", List.of("alarm-limit")));

        var schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        var schemaMapper = VMFJsonSchemaGenerator.createObjectMapper();
        for (boolean references : List.of(false, true)) {
            var plainMapper = new ObjectMapper()
                    .registerModule(VMFJacksonModule.newInstance(MODE).withReferences(references));
            var strictMapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE)
                    .withReferences(references).withModelValidator(rule));
            var generator = VMFJsonSchemaGenerator.newInstance(MODE).withReferences(references);
            var plainSchema = schemaFactory.getSchema(schemaMapper.valueToTree(generator.generateSchema(InstrumentGroup.class)));
            var strictSchema = schemaFactory.getSchema(schemaMapper.valueToTree(generator
                    .withTypeConstraint(Instrument.class, condition).generateSchema(InstrumentGroup.class)));

            var loaded = plainMapper.readValue(invalid, InstrumentGroup.class);
            assertTrue(loaded.vmf().content().validate().isValid());
            var invalidDocument = plainMapper.readTree(invalid);
            assertTrue(plainSchema.validate(invalidDocument).isEmpty());

            var error = assertThrows(VMFValidationException.class, () -> strictMapper.readValue(invalid, InstrumentGroup.class));
            var issues = error.getReport().getIssues();
            assertEquals(1, issues.size());
            var issue = issues.get(0);
            assertEquals("instrument.alarm-limit.required", issue.getCode());
            assertEquals("/instruments/0/alarmLimit", issue.getPath());
            assertEquals("alarmLimit", issue.getProperty().orElseThrow().getName());
            assertEquals(issue.toString(), rule.validate(loaded).getIssues().get(0).toString());
            assertTrue(strictSchema.validate(invalidDocument).stream()
                    .anyMatch(message -> "required".equals(message.getType()) && message.getMessage().contains("alarm-limit")));

            var fixed = strictMapper.readValue(repaired, InstrumentGroup.class);
            assertEquals(8.0, fixed.getInstruments().get(0).getAlarmLimit());
            assertTrue(rule.validate(fixed).isValid());
            assertTrue(strictSchema.validate(plainMapper.readTree(repaired)).isEmpty());
            assertTrue(rule.validate(strictMapper.readValue(inactive, InstrumentGroup.class)).isValid());
            assertTrue(strictSchema.validate(plainMapper.readTree(inactive)).isEmpty());
        }
    }
}
