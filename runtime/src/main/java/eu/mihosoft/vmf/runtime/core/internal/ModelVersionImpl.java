/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 */
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vmf.runtime.core.ModelVersion;

/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 *
 * Created by miho on 03.03.17.
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
class ModelVersionImpl implements ModelVersion {
    private final long timestamp;
    private final long modelVersion;

    public ModelVersionImpl(long timestamp, long modelVersion) {
        this.timestamp = timestamp;
        this.modelVersion = modelVersion;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    @Override
    public long versionNumber() {
        return modelVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ModelVersionImpl that = (ModelVersionImpl) o;

        if (timestamp != that.timestamp) return false;
        return modelVersion == that.modelVersion;
    }

    @Override
    public int hashCode() {
        int result = (int) (timestamp ^ (timestamp >>> 32));
        result = 31 * result + (int) (modelVersion ^ (modelVersion >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return String.format("ModelVersion{version: %d, timestamp: %d}", modelVersion, timestamp);
    }
}
