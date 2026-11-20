/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
 * If you use this software in scientific research please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library. InComputing and Visualization in Science, 2013, 16(4),
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.runtime.core.internal;

/** Keeps containment notifications synchronous while an edit completes its structural work. */
public final class ContainmentMutation implements AutoCloseable {
    private static final ThreadLocal<Stack> SCOPES = ThreadLocal.withInitial(Stack::new);
    private static final ContainmentMutation JOINED = new ContainmentMutation(null, null);

    private final Stack stack;
    private final ContainmentMutation previous;
    private ContainmentMutation next;
    private Throwable observerFailure;

    private ContainmentMutation(Stack stack, ContainmentMutation previous) {
        this.stack = stack;
        this.previous = previous;
    }

    public static ContainmentMutation open() {
        return open(false);
    }

    static ContainmentMutation open(boolean joinExisting) {
        var stack = SCOPES.get();
        var active = stack.active;
        if (active == null) {
            stack.active = stack.first;
        } else if (joinExisting) {
            return JOINED;
        } else {
            if (active.next == null) active.next = new ContainmentMutation(stack, active);
            stack.active = active.next;
        }
        return stack.active;
    }

    /** Used only at synchronous listener dispatch sites. */
    public static void defer(Throwable failure) {
        var active = SCOPES.get().active;
        if (active == null) {
            ObserverFailures.rethrow(failure);
        } else {
            active.observerFailure = ObserverFailures.add(active.observerFailure, failure);
        }
    }

    @Override
    public void close() {
        if (stack == null) return;
        stack.active = previous;
        var failure = observerFailure;
        observerFailure = null;
        ObserverFailures.rethrow(failure);
    }

    private static final class Stack {
        private final ContainmentMutation first = new ContainmentMutation(this, null);
        private ContainmentMutation active;
    }
}
