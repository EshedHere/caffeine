/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.sampled;

import static java.util.Locale.US;
import static java.util.stream.Collectors.toUnmodifiableSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.policy.esp.BaseNode;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.SharedBuffer;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.KeyOnlyPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * A cache that uses a sampled array of entries to implement simple page replacement algorithms.
 * <p>
 * The sampling approach for an approximation of classical policies is described
 * <a href="http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.110.8469">Efficient Randomized Web
 * Cache Replacement Schemes Using Samples from Past Eviction Times</a>. The Hyperbolic algorithm is
 * a newer addition to this family and is described in
 * <a href="https://www.usenix.org/system/files/conference/atc17/atc17-blankstein.pdf">Hyperbolic
 * Caching: Flexible Caching for Web Applications</a>.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class SampledPolicy implements KeyOnlyPolicy {
  final Long2ObjectMap<Node> data;
  public PolicyStats policyStats;
  final EvictionPolicy policy;
  public Sample sampleStrategy;
  final Admittor admittor;
  public int maximumSize;
  public int sampleSize;
  final Random random;
  final Node[] table;
  //SharedBuffer sharedBuffer;


  long tick;

  public SampledPolicy(Admission admission, EvictionPolicy policy, Config config) {
    this.policyStats = new PolicyStats(admission.format("sampled." + policy.label()));
    this.admittor = admission.from(config, policyStats);

    SampledSettings settings = new SampledSettings(config);
    this.maximumSize = Math.toIntExact(settings.PPmaximumSize()); //need to change
    this.sampleStrategy = settings.sampleStrategy();
    this.random = new Random(settings.randomSeed());

    this.data = new Long2ObjectOpenHashMap<>();
    this.sampleSize = settings.sampleSize();
    this.table = new Node[maximumSize + 1];
    this.policy = policy;


  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config, EvictionPolicy policy) {
    BasicSettings settings = new BasicSettings(config);
    return settings.admission().stream().map(admission ->
      new SampledPolicy(admission, policy, config)
    ).collect(toUnmodifiableSet());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(long key) {
    Node node = data.get(key);
    admittor.record(key);
    long now = ++tick;
    if (node == null) {
      node = new Node(key, data.size(), now);
      policyStats.recordOperation();
      policyStats.recordMiss();
      table[node.index] = node;
      data.put(key, node);
      evict(node);
    } else {
      policyStats.recordOperation();
      policyStats.recordHit();
      node.accessTime = now;
      node.frequency++;
    }
  }

  /** Evicts if the map exceeds the maximum capacity. */
  private void evict(Node candidate) {
    System.out.println(data.size());
    if (data.size() > maximumSize) {
      List<Node> sample = (policy == EvictionPolicy.RANDOM)
          ? Arrays.asList(table)
          : sampleStrategy.sample(table, candidate, sampleSize, random, policyStats);
      Node victim = policy.select(sample, random, tick);
      policyStats.recordEviction();

      if (admittor.admit(candidate.key, victim.key)) {

        //move VICTIM to buffer
//        System.out.println("The victim key from sampled: "+victim.key);

//        SharedBuffer.insertData(victim);
//        System.out.println("The victim key read from sampled: "+SharedBuffer.getBufferKey());


        removeFromTable(victim);
        data.remove(victim.key);

        System.out.println("LRU: "+data);
      } else {
        //move candidate to buffer
//        SharedBuffer.insertData(candidate);
//        System.out.println("The candidate key is: "+victim.key);


        removeFromTable(candidate);
        data.remove(candidate.key);
      }
    }

  }

  /** Removes the node from the table and adds the index to the free list. */
  private void removeFromTable(Node node) {
    int last = data.size() - 1;
    table[node.index] = table[last];
    table[node.index].index = node.index;
    table[last] = null;
  }

  /** The algorithms to choose a random sample with. */
  public enum Sample {
    GUESS {
      @SuppressWarnings("PMD.AvoidReassigningLoopVariables")
      @Override public <E> List<E> sample(E[] elements, E candidate,
          int sampleSize, Random random, PolicyStats policyStats) {
        var sample = new ArrayList<E>(sampleSize);
        policyStats.addOperations(sampleSize);
        while (sample.size() < sampleSize) {
          int index = random.nextInt(elements.length);
          if (elements[index] != candidate) {
            sample.add(elements[index]);
          }
        }
        return sample;
      }
    },
    RESERVOIR {
      @Override public <E> List<E> sample(E[] elements, E candidate,
          int sampleSize, Random random, PolicyStats policyStats) {
        List<E> sample = new ArrayList<>(sampleSize);
        policyStats.addOperations(elements.length);
        int count = 0;
        for (E e : elements) {
          if (e == candidate) {
            continue;
          }
          count++;
          if (sample.size() <= sampleSize) {
            sample.add(e);
          } else {
            int index = random.nextInt(count);
            if (index < sampleSize) {
              sample.set(index, e);
            }
          }
        }
        return sample;
      }
    },
    SHUFFLE {
      @Override public <E> List<E> sample(E[] elements, E candidate,
          int sampleSize, Random random, PolicyStats policyStats) {
        List<E> sample = new ArrayList<>(Arrays.asList(elements));
        policyStats.addOperations(elements.length);
        Collections.shuffle(sample, random);
        sample.remove(candidate);
        return sample.subList(0, sampleSize);
      }
    };

    abstract <E> List<E> sample(E[] elements, E candidate,
        int sampleSize, Random random, PolicyStats policyStats);
  }

  /** The replacement policy. */
  public enum EvictionPolicy {

    /** Evicts entries based on insertion order. */
    FIFO {
      @Override Node select(List<Node> sample, Random random, long tick) {
        return sample.stream().min(Comparator.comparingLong(
            node -> node.insertionTime)).orElseThrow();
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    LRU {
      @Override Node select(List<Node> sample, Random random, long tick) {
        System.out.println("evict candidate: "+sample.stream().min(Comparator.comparingLong(node -> node.accessTime)).orElseThrow());
        return sample.stream().min(Comparator.comparingLong(node -> node.accessTime)).orElseThrow();
      }
    },

    /** Evicts entries based on how recently they are used, with the least recent evicted first. */
    MRU {
      @Override Node select(List<Node> sample, Random random, long tick) {
        return sample.stream().max(Comparator.comparingLong(node -> node.accessTime)).orElseThrow();
      }
    },

    /**
     * Evicts entries based on how frequently they are used, with the least frequent evicted first.
     */
    LFU {
      @Override Node select(List<Node> sample, Random random, long tick) {
        return sample.stream().min(Comparator.comparingInt(node -> node.frequency)).orElseThrow();
      }
    },

    /**
     * Evicts entries based on how frequently they are used, with the most frequent evicted first.
     */
    MFU {
      @Override Node select(List<Node> sample, Random random, long tick) {
        return sample.stream().max(Comparator.comparingInt(node -> node.frequency)).orElseThrow();
      }
    },

    /** Evicts a random entry. */
    RANDOM {
      @Override Node select(List<Node> sample, Random random, long tick) {
        int victim = random.nextInt(sample.size());
        return sample.get(victim);
      }
    },

    /** Evicts entries based on how frequently they are used divided by their age. */
    HYPERBOLIC {
      @Override Node select(List<Node> sample, Random random, long tick) {
        return sample.stream().min(Comparator.comparingDouble(
            node -> hyperbolic(node, tick))).orElseThrow();
      }
      double hyperbolic(Node node, long tick) {
        return node.frequency / (double) (tick - node.insertionTime);
      }
    };

    public String label() {
      return "sampled." + StringUtils.capitalize(name().toLowerCase(US));
    }

    /** Determines which node to evict. */
    abstract Node select(List<Node> sample, Random random, long tick);
  }

   public static class Node extends BaseNode {
    final long key;
    final long insertionTime;

    long accessTime;
    public int frequency;
    int index;


    public Node(long key, int index, long tick) {
      this.insertionTime = tick;
      this.accessTime = tick;
      this.index = index;
      this.key = key;

      super.key=this.key;
    }
     Node(BaseNode basenode, long tick){
       this.insertionTime = tick;
       this.accessTime = tick;
       this.key = basenode.key;
       this.recency=basenode.recency;
       super.key=this.key;

     }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("index", index)
          .toString();
    }
  }

  static final class SampledSettings extends BasicSettings {
    public SampledSettings(Config config) {
      super(config);
    }
    public int sampleSize() {
      return config().getInt("sampled.size");
    }
    public Sample sampleStrategy() {
      return Sample.valueOf(config().getString("sampled.strategy").toUpperCase(US));
    }
  }
}
