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

import eu.mihosoft.vcollections.VSet;
import eu.mihosoft.vcollections.VSetChangeEvent;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** One membership edit, including a bulk replacement, on a scalar set. */
final class SetChangeImpl implements Change {
    private final VObject object;
    private final String name;
    private final VSetChangeEvent<Object> event;
    private final long timestamp = System.nanoTime();

    SetChangeImpl(VObject object, String name, VSetChangeEvent<Object> event) {
        this.object = object;
        this.name = name;
        this.event = event;
    }

    @Override public VObject object() { return object; }
    @Override public String propertyName() { return name; }
    @Override public long getTimestamp() { return timestamp; }
    @Override public ChangeType getType() { return ChangeType.SET; }
    @Override public Optional<VSetChangeEvent<Object>> setChange() { return Optional.of(event); }

    Object deliveryIdentity() { return event; }

    @Override
    public boolean isUndoable() {
        VSet<Object> current = event.source();
        if (current.isReadOnly() || !current.containsAll(event.added())
                || !Collections.disjoint(current, event.removed())) return false;
        int upper = property(object).getMultiplicity().getUpper();
        long restoredSize = (long) current.size() - event.added().size() + event.removed().size();
        return upper < 0 || restoredSize <= upper;
    }

    @Override
    public void undo() {
        if (isUndoable()) edit(event.source(), event.added(), event.removed());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void apply(VObject target) {
        Property destination = property(target);
        if (!destination.getType().isSetType()
                || !destination.getType().getDescriptor().equals(property(object).getType().getDescriptor())) {
            throw new IllegalArgumentException("Incompatible set property: " + name);
        }
        edit((VSet<Object>) destination.get(), event.removed(), event.added());
    }

    private Property property(VObject target) {
        return target.vmf().reflect().propertyByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown set property: " + name));
    }

    private static void edit(VSet<Object> target, Set<Object> remove, Set<Object> add) {
        Set<Object> next = new LinkedHashSet<>(target);
        next.removeAll(remove);
        next.addAll(add);
        target.replaceContents(next);
    }
}
