/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.propertytype;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

@SuppressWarnings("deprecation")
public class CustomLoaderPropertyTest {

    @Test
    public void propertyResolutionStillCallsCustomLoadersAndReadsCurrentMetadata() {
        var loader = new ObservingLoader();
        String[] typeNames = {String.class.getName()};
        var parent = (VObjectInternal) Proxy.newProxyInstance(loader, new Class<?>[]{VObjectInternal.class},
                (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "_vmf_getPropertyIdByName": return 0;
                        case "_vmf_getPropertyTypes": return new int[]{-1};
                        case "_vmf_getPropertyTypeNames": return typeNames;
                        default: throw new AssertionError(method);
                    }
                });
        int requestsBeforeResolution = loader.stringRequests;
        var first = Property.newInstance(parent, "value", false).getType();
        var second = Property.newInstance(parent, "value", false).getType();

        assertEquals(requestsBeforeResolution + 2, loader.stringRequests);
        assertEquals(first, second);
        assertNotSame(first, second);

        typeNames[0] = Integer.class.getName();
        var changed = Property.newInstance(parent, "value", false).getType();
        assertEquals(String.class.getName(), first.getName());
        assertEquals(Integer.class.getName(), changed.getName());
    }

    private static final class ObservingLoader extends ClassLoader {
        private int stringRequests;

        private ObservingLoader() {
            super(VObjectInternal.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (String.class.getName().equals(name)) stringRequests++;
            return super.loadClass(name, resolve);
        }
    }
}
