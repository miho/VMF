/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
 * 181â€“192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.list_set_replay;

import eu.mihosoft.vmf.runtime.core.Change;
import org.junit.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.Assert.*;

public class ListSetReplayTest {
    private void roundTrip(List<String> input, Consumer<ListReplayModel> edit, List<String> expected) {
        ListReplayModel source=ListReplayModel.newInstance();source.getValues().addAll(input);
        ListReplayModel target=source.clone();var changes=source.vmf().changes();changes.start();changes.startTransaction();
        edit.accept(source);changes.publishTransaction();changes.stop();
        assertEquals(expected,source.getValues());
        List<Change> records=new ArrayList<>(changes.all());assertFalse(records.isEmpty());
        records.forEach(c->c.apply(target));assertEquals(expected,target.getValues());
        changes.transactions().get(0).undo();assertEquals(input,source.getValues());
        records.forEach(c->c.apply(source));assertEquals(expected,source.getValues());
    }
    @Test public void singleIndexInsideLongerList() {roundTrip(Arrays.asList("a","b","c"),m->m.getValues().set(1,"x"),Arrays.asList("a","x","c"));}
    @Test public void middleRangeInsideLongerList() {roundTrip(Arrays.asList("a","b","c","d"),m->m.getValues().setAll(1,Arrays.asList("x","y")),Arrays.asList("a","x","y","d"));}
    @Test public void iteratorSetKeepsItsIndex() {roundTrip(Arrays.asList("a","b","c"),m->{var it=m.getValues().listIterator(2);it.next();it.set("x");},Arrays.asList("a","b","x"));}
    @Test public void swapReplaysAndUndoesBothSets() {roundTrip(Arrays.asList("a","b","c"),m->Collections.swap(m.getValues(),0,2),Arrays.asList("c","b","a"));}
    @Test public void completeRangeStillWorks() {roundTrip(Arrays.asList("a","b"),m->m.getValues().setAll(0,Arrays.asList("x","y")),Arrays.asList("x","y"));}
    @Test public void backwardIteratorSetKeepsItsIndex() {roundTrip(Arrays.asList("a","b","c"),m->{var it=m.getValues().listIterator(2);it.previous();it.set("x");it.set("y");},Arrays.asList("a","y","c"));}
    @Test public void backwardIteratorRemoveKeepsItsIndex() {roundTrip(Arrays.asList("a","b","c"),m->{var it=m.getValues().listIterator(2);it.previous();it.remove();},Arrays.asList("a","c"));}
}
