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
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vcollections.VMapChangeEvent;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** An edit to the keys of one scalar map property. */
final class MapChangeImpl implements Change {
    private final VObject object;
    private final String property;
    private final VMapChangeEvent<String, Object> event;
    private final long timestamp = System.nanoTime();

    MapChangeImpl(VObject object, String property, VMapChangeEvent<String, Object> event) {
        this.object = object;
        this.property = property;
        this.event = event;
    }

    @Override public VObject object() { return object; }
    @Override public String propertyName() { return property; }
    @Override public long getTimestamp() { return timestamp; }
    @Override public ChangeType getType() { return ChangeType.MAP; }
    @Override public Optional<VMapChangeEvent<String, Object>> mapChange() { return Optional.of(event); }

    @Override
    public boolean isUndoable() {
        if (!(object instanceof VObjectInternalModifiable)) return false;
        Map<String, Object> current = event.source();
        Map<String, Object> after = event.added().entries();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!current.containsKey(entry.getKey())
                    || !Objects.equals(current.get(entry.getKey()), entry.getValue())) return false;
        }
        for (String key : event.removed().keys()) {
            if (!after.containsKey(key) && current.containsKey(key)) return false;
        }
        return true;
    }

    @Override
    public void undo() {
        if (isUndoable()) edit(event.source(), event.added().entries(), event.removed().entries());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void apply(VObject target) {
        var propertyInfo = target.vmf().reflect().propertyByName(property)
                .orElseThrow(() -> new IllegalArgumentException("Unknown map property: " + property));
        if (!propertyInfo.getType().isMapType()
                || !propertyInfo.getType().getDescriptor().equals(
                    object.vmf().reflect().propertyByName(property).orElseThrow().getType().getDescriptor())) {
            throw new IllegalArgumentException("Incompatible map property: " + property);
        }
        edit((VMap<String, Object>) propertyInfo.get(), event.removed().entries(), event.added().entries());
    }

    private static void edit(VMap<String, Object> target, Map<String, Object> before, Map<String, Object> after) {
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) target.remove(key);
        }
        target.putAll(after);
    }
}
