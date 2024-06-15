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
package com.github.benmanes.caffeine.cache.simulator.admission;

import java.util.Random;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor.KeyOnlyAdmittor;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.ClimberResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.IncrementalResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.IndicatorResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin64.CountMin64TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.admission.perfect.PerfectFrequency;
import com.github.benmanes.caffeine.cache.simulator.admission.table.RandomRemovalFrequencyTable;
import com.github.benmanes.caffeine.cache.simulator.admission.tinycache.TinyCacheAdapter;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;

/**
 * Admits new entries based on the estimated frequency of its historic use.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */

/**
 * This admittor is an alternative fro TinyLFU, the cahnges apllied here makes this
 *
 *  sketch a global frequency tracker, the frequncies are updated by the pipeline manager
 *
 *
 */
public final class PipelineAdmittor implements Admittor {
  private static volatile PipelineAdmittor instance = null;
  private final PolicyStats policyStats;
  public  Frequency sketch;
  private final Random random;

  private final double probability;
  private final int threshold;

  public PipelineAdmittor(Config config, PolicyStats policyStats) {
    var settings = new BasicSettings(config);
    this.random = new Random(settings.randomSeed());
    this.sketch = makeSketch(settings);
    this.policyStats = policyStats;
    if (settings.tinyLfu().jitter().enabled()) {
      this.threshold = settings.tinyLfu().jitter().threshold();
      this.probability = settings.tinyLfu().jitter().probability();
    } else {
      this.threshold = Integer.MAX_VALUE;
//      this.threshold = 1;
      this.probability = 1;
//      this.probability = 0;
    }
  }

  public Frequency makeSketch(BasicSettings settings) {
    String type = settings.tinyLfu().sketch();
    if (type.equalsIgnoreCase("count-min-4")) {
      String reset = settings.tinyLfu().countMin4().reset();
      if (reset.equalsIgnoreCase("periodic")) {
        return new PeriodicResetCountMin4(settings.config());
      } else if (reset.equalsIgnoreCase("incremental")) {
        return new IncrementalResetCountMin4(settings.config());
      } else if (reset.equalsIgnoreCase("climber")) {
        return new ClimberResetCountMin4(settings.config());
      } else if (reset.equalsIgnoreCase("indicator")) {
        return new IndicatorResetCountMin4(settings.config());
      } else {
        throw new IllegalStateException("Unknown reset type: " + reset);
      }
    } else if (type.equalsIgnoreCase("count-min-64")) {
      return new CountMin64TinyLfu(settings.config());
    } else if (type.equalsIgnoreCase("random-table")) {
      return new RandomRemovalFrequencyTable(settings.config());
    } else if (type.equalsIgnoreCase("tiny-table")) {
      return new TinyCacheAdapter(settings.config());
    } else if (type.equalsIgnoreCase("perfect-table")) {
      return new PerfectFrequency(settings.config());
    }
    throw new IllegalStateException("Unknown sketch type: " + type);
  }

  public int frequency(long key) {
    return sketch.frequency(key);
  }

  @Override
  public void record(long key) {
//    sketch.increment(key);
    //pipeline manager will directly increase the value of the key (freq)
  }
  @Override
  public boolean admit(AccessEvent candidate, AccessEvent victim) {
    return true;
  }
  public void record(AccessEvent event) {

  }

  @Override
  public boolean admit(long candidateKey, long victimKey) { //if true admit victim
    sketch.reportMiss();

    int victimFreq = sketch.frequency(victimKey);
    int candidateFreq = sketch.frequency(candidateKey);
//    System.out.println("candidate is"+candidateKey+" freq is: "+candidateFreq+", VICTIM: "+victimKey+" freq is "+victimFreq);
    if ((candidateFreq > victimFreq)
      || ((candidateFreq >= threshold) && (random.nextFloat() < probability))) {
      policyStats.recordAdmission();
      return true;
    }
    policyStats.recordRejection();
    return false;
//    return true;// ALWAYS ADMIT
  }

//  public static GlobalAdmittor getInstance(Config config, PolicyStats policyStats) {
//    if (instance == null) { //First check
//      synchronized (GlobalAdmittor.class) {
//        if(instance == null) { //Second check
//          instance = new GlobalAdmittor(config, policyStats);
//        }
//      }
//    }
//    return instance;
//  }
}
