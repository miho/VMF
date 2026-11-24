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
package eu.mihosoft.vmftest.keyed.invalid.vmfmodel;

import eu.mihosoft.vmf.VMF;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.io.MemoryResourceSet;
import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class InvalidKeyedPropertiesTest {
    @Test public void rejectsUnsupportedKeyDeclarationsAtGeneration() {
        invalid("containment list", NonOwning.class, InvalidEntry.class);
        invalid("unknown key", Unknown.class, InvalidEntry.class);
        invalid("stable scalar", ListKey.class, InvalidEntry.class);
        invalid("stable scalar", ExternalKey.class, InvalidEntry.class);
        invalid("stable scalar", OwningKey.class, InvalidEntry.class);
        invalid("stable scalar", ReferenceKey.class, ReferenceEntry.class, RedeclaredReference.class, InvalidEntry.class);
        invalid("lookup method conflicts", Ambiguous.class, InvalidEntry.class);
        invalid("lookup method conflicts", BehaviorClash.class, FirstKey.class, InvalidEntry.class);
        invalid("must preserve its entry type", Narrowed.class, FirstKey.class, SpecialEntry.class, InvalidEntry.class);
        invalid("conflicting entry keys", ChangedKey.class, FirstKey.class, InvalidEntry.class);
        invalid("conflicting entry keys", Diamond.class, FirstKey.class, SecondKey.class, InvalidEntry.class);
    }

    private void invalid(String diagnostic, Class<?>... types) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> VMF.generate(new MemoryResourceSet(), types));
        assertTrue(failure.getMessage(), failure.getMessage().contains(diagnostic));
    }
}

interface InvalidEntry {
    String getBByC();
    String getC();
    String[] getNames();
    java.util.Date getDate();
    @Contains InvalidEntry getOwned();
}
interface NonOwning { @Keyed(by="c") InvalidEntry[] getEntries(); }
interface Unknown { @Contains @Keyed(by="missing") InvalidEntry[] getEntries(); }
interface ListKey { @Contains @Keyed(by="names") InvalidEntry[] getEntries(); }
interface ExternalKey { @Contains @Keyed(by="date") InvalidEntry[] getEntries(); }
interface OwningKey { @Contains @Keyed(by="owned") InvalidEntry[] getEntries(); }
interface Ambiguous {
    @Contains @Keyed(by="bByC") InvalidEntry[] getA();
    @Contains @Keyed(by="c") InvalidEntry[] getAByB();
}

@eu.mihosoft.vmf.core.InterfaceOnly
interface FirstKey { @Contains @Keyed(by="c") InvalidEntry[] getEntries(); }
@eu.mihosoft.vmf.core.InterfaceOnly
interface SecondKey { @Contains @Keyed(by="bByC") InvalidEntry[] getEntries(); }
interface ChangedKey extends FirstKey { @Override @Keyed(by="bByC") InvalidEntry[] getEntries(); }
interface Diamond extends FirstKey, SecondKey {}
interface BehaviorClash extends FirstKey {
    @eu.mihosoft.vmf.core.DelegateTo(className="unused.Behavior")
    InvalidEntry findEntriesByC(String key);
}
interface SpecialEntry extends InvalidEntry {}
interface Narrowed extends FirstKey { @Override SpecialEntry[] getEntries(); }

interface ReferenceEntry { InvalidEntry getTarget(); }
interface RedeclaredReference extends ReferenceEntry { @Override @Contains InvalidEntry getTarget(); }
interface ReferenceKey { @Contains @Keyed(by="target") ReferenceEntry[] getEntries(); }
