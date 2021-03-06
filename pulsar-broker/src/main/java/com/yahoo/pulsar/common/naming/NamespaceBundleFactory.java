/**
 * Copyright 2016 Yahoo Inc.
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
 */
package com.yahoo.pulsar.common.naming;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.yahoo.pulsar.common.policies.data.Policies.FIRST_BOUNDARY;
import static com.yahoo.pulsar.common.policies.data.Policies.LAST_BOUNDARY;
import static java.lang.String.format;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Range;
import com.google.common.hash.HashFunction;
import com.yahoo.pulsar.broker.PulsarService;
import com.yahoo.pulsar.broker.admin.AdminResource;
import com.yahoo.pulsar.common.policies.data.BundlesData;
import com.yahoo.pulsar.common.policies.data.LocalPolicies;
import com.yahoo.pulsar.zookeeper.ZooKeeperCacheListener;
import static com.yahoo.pulsar.broker.cache.LocalZooKeeperCacheService.LOCAL_POLICIES_ROOT;

public class NamespaceBundleFactory implements ZooKeeperCacheListener<LocalPolicies> {
    private static final Logger LOG = LoggerFactory.getLogger(NamespaceBundleFactory.class);

    private final HashFunction hashFunc;

    private final LoadingCache<NamespaceName, NamespaceBundles> bundlesCache;

    private final PulsarService pulsar;

    public NamespaceBundleFactory(PulsarService pulsar, HashFunction hashFunc) {
        this.hashFunc = hashFunc;

        this.bundlesCache = CacheBuilder.newBuilder().build(new CacheLoader<NamespaceName, NamespaceBundles>() {
            @Override
            public NamespaceBundles load(NamespaceName namespace) throws Exception {
                if (pulsar == null || pulsar.getConfigurationCache() == null) {
                    return getBundles(namespace, null);
                }

                try {
                    // Read the static bundle data from the policies
                    LocalPolicies policies = pulsar.getLocalZkCacheService().policiesCache()
                            .get(AdminResource.joinPath(LOCAL_POLICIES_ROOT, namespace.toString()));
                    return getBundles(namespace, policies.bundles);
                } catch (NoNodeException nne) {
                    // No policies defined for the corresponding namespace
                    return getBundles(namespace, null);
                }
            }
        });

        if (pulsar != null && pulsar.getConfigurationCache() != null) {
            pulsar.getLocalZkCacheService().policiesCache().registerListener(this);
        }

        this.pulsar = pulsar;
    }

    @Override
    public void onUpdate(String path, LocalPolicies data, Stat stat) {
        final NamespaceName namespace = new NamespaceName(getNamespaceFromPoliciesPath(path));

        try {
            LOG.info("Policy updated for namespace {}, refreshing the bundle cache.", namespace);
            // invalidate the bundle cache to fetch new bundle data from the policies
            bundlesCache.invalidate(namespace);
        } catch (Exception e) {
            LOG.error("Failed to update the policy change for ns {}", namespace, e);
        }
    }

    /**
     * checks if the local broker is the owner of the namespace bundle
     *
     * @param nsBundle
     * @return
     */
    private boolean isOwner(NamespaceBundle nsBundle) {
        if (pulsar != null) {
            return pulsar.getNamespaceService().getOwnershipCache().getOwnedServiceUnit(nsBundle) != null;
        }
        return false;
    }

    public void invalidateBundleCache(NamespaceName namespace) {
        bundlesCache.invalidate(namespace);
    }

    public NamespaceBundles getBundles(NamespaceName nsname) throws Exception {
        return bundlesCache.get(nsname);
    }

    public NamespaceBundle getBundle(NamespaceName nsname, Range<Long> hashRange) throws Exception {
        return new NamespaceBundle(nsname, hashRange, this);
    }

    public NamespaceBundle getFullBundle(NamespaceName fqnn) throws Exception {
        return bundlesCache.get(fqnn).getFullBundle();
    }

    public long getLongHashCode(String name) {
        return this.hashFunc.hashString(name, Charsets.UTF_8).padToLong();
    }

    public NamespaceBundles getBundles(NamespaceName nsname, BundlesData bundleData) throws Exception {
        long[] partitions;
        if (bundleData == null) {
            partitions = new long[] { Long.decode(FIRST_BOUNDARY), Long.decode(LAST_BOUNDARY) };
        } else {
            partitions = new long[bundleData.boundaries.size()];
            for (int i = 0; i < bundleData.boundaries.size(); i++) {
                partitions[i] = Long.decode(bundleData.boundaries.get(i));
            }
        }
        return new NamespaceBundles(nsname, partitions, this);
    }

    public static BundlesData getBundlesData(NamespaceBundles bundles) throws Exception {
        if (bundles == null) {
            return new BundlesData();
        } else {
            List<String> boundaries = Arrays.stream(bundles.partitions).boxed().map(p -> format("0x%08x", p))
                    .collect(Collectors.toList());
            return new BundlesData(boundaries);
        }
    }

    /**
     * Fetches {@link NamespaceBundles} from cache for a given namespace. finds target bundle, split into numBundles and
     * returns new {@link NamespaceBundles} with newly split bundles into it.
     *
     * @param targetBundle
     *            {@link NamespaceBundle} needs to be split
     * @param numBundles
     *            split into numBundles
     * @return List of split {@link NamespaceBundle} and {@link NamespaceBundles} that contains final bundles including
     *         split bundles for a given namespace
     * @throws Exception
     */
    public Pair<NamespaceBundles, List<NamespaceBundle>> splitBundles(NamespaceBundle targetBundle, int numBundles)
            throws Exception {
        checkNotNull(targetBundle, "can't split null bundle");
        checkNotNull(targetBundle.getNamespaceObject(), "namespace must be present");
        NamespaceName nsname = targetBundle.getNamespaceObject();
        NamespaceBundles sourceBundle = bundlesCache.get(nsname);

        final int lastIndex = sourceBundle.partitions.length - 1;

        final long[] partitions = new long[sourceBundle.partitions.length + (numBundles - 1)];
        int pos = 0;
        int splitPartition = -1;
        for (int i = 0; i < lastIndex; i++) {
            final Range<Long> range = targetBundle.getKeyRange();
            if (sourceBundle.partitions[i] == range.lowerEndpoint()
                    && (range.upperEndpoint() == sourceBundle.partitions[i + 1])) {
                splitPartition = i;
                Long maxVal = sourceBundle.partitions[i + 1];
                Long minVal = sourceBundle.partitions[i];
                Long segSize = (maxVal - minVal) / numBundles;
                partitions[pos++] = minVal;
                Long curPartition = minVal + segSize;
                for (int j = 0; j < numBundles - 1; j++) {
                    partitions[pos++] = curPartition;
                    curPartition += segSize;
                }
            } else {
                partitions[pos++] = sourceBundle.partitions[i];
            }
        }
        partitions[pos] = sourceBundle.partitions[lastIndex];
        if (splitPartition != -1) {
            NamespaceBundles splittedNsBundles = new NamespaceBundles(nsname, partitions, this);
            List<NamespaceBundle> splittedBundles = splittedNsBundles.getBundles().subList(splitPartition,
                    (splitPartition + numBundles));
            return new ImmutablePair<NamespaceBundles, List<NamespaceBundle>>(splittedNsBundles, splittedBundles);
        }
        return null;
    }

    public static void validateFullRange(SortedSet<String> partitions) {
        checkArgument(partitions.first().equals(FIRST_BOUNDARY) && partitions.last().equals(LAST_BOUNDARY));
    }

    public static NamespaceBundleFactory createFactory(HashFunction hashFunc) {
        return new NamespaceBundleFactory(null, hashFunc);
    }

    public static boolean isFullBundle(String bundleRange) {
        return bundleRange.equals(String.format("%s_%s", FIRST_BOUNDARY, LAST_BOUNDARY));
    }

    public static String getDefaultBundleRange() {
        return String.format("%s_%s", FIRST_BOUNDARY, LAST_BOUNDARY);
    }

    /*
     * @param path - path for the namespace policies ex. /admin/policies/prop/cluster/namespace
     *
     * @returns namespace with path, ex. prop/cluster/namespace
     */
    public static String getNamespaceFromPoliciesPath(String path) {
        if (path.isEmpty()) {
            return path;
        }
        // String before / is considered empty string by splitter
        Iterable<String> splitter = Splitter.on("/").limit(6).split(path);
        Iterator<String> i = splitter.iterator();
        // skip first three - "","admin", "policies"
        i.next();
        i.next();
        i.next();
        // prop, cluster, namespace
        return Joiner.on("/").join(i.next(), i.next(), i.next());
    }

}
