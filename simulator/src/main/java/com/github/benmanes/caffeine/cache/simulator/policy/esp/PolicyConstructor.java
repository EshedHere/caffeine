package com.github.benmanes.caffeine.cache.simulator.policy.esp;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.GlobalAdmittor;
import com.github.benmanes.caffeine.cache.simulator.admission.PipelineTinyLfu;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.SegmentedLruPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.linked.LinkedPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.greedy_dual.GDWheelPolicy;

import com.github.benmanes.caffeine.cache.simulator.policy.sampled.SampledPolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TwoQueuePolicy;
import com.github.benmanes.caffeine.cache.simulator.policy.two_queue.TuQueuePolicy;

import com.typesafe.config.Config;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;
import com.typesafe.config.ConfigFactory;


public class PolicyConstructor{
  // POLICIES
  Config inConfig;
  PolicyStats IntraStats;

  BasicSettings tempLinkedLRUSettings;
  GDWheelSettings tempGDWheelSettings;


  String customConfigOverrides;

  Config customConfig;
  Config combinedConfig;
   boolean weighted;
  Set<Characteristic> characteristics;
  int sampleSize;
  String sampleStrategy;
  double percentProtected;

  public PolicyConstructor(Config config) {
  this.inConfig = config;
  this.IntraStats = new PolicyStats("Intra-Pipeline (IGNORE)");
  this.weighted = false;
  this.characteristics = Set.of(WEIGHTED);

  }
  public Policy createPolicy(String policyName, int maxSize){
    switch (policyName) {
      //--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      case "SampledLRU":
        // Extract the overriding values from the original config
         sampleSize = this.inConfig.getInt("esp.sampled.size");
         sampleStrategy = this.inConfig.getString("esp.sampled.strategy");
        // Create the custom configuration overrides as a string
         customConfigOverrides =
           "maximum-size = " + maxSize + "\n"+
          "sampled.size = " + maxSize + "\n" +
            "sampled.strategy = " + sampleStrategy;
         System.out.println(customConfigOverrides);
        // Parse the custom configuration overrides
         customConfig = ConfigFactory.parseString(customConfigOverrides);
        // Combine the custom configuration with the existing one
         combinedConfig = customConfig.withFallback(this.inConfig);
        // Create a new SampledPolicy with the combined config
        SampledPolicy sampledLruPolicy = new SampledPolicy(
          Admission.ALWAYS,
          SampledPolicy.EvictionPolicy.LRU,
          combinedConfig
        );
        sampledLruPolicy.admittor=GlobalAdmittor.getInstance(this.inConfig, IntraStats, 3, 1);

        return sampledLruPolicy;
      //--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

      case "SampledFIFO":
        // Extract the overriding values from the original config
        //print maxSize value
         sampleSize = this.inConfig.getInt("esp.sampled.size");
         sampleStrategy = this.inConfig.getString("esp.sampled.strategy");
        // Create the custom configuration overrides as a string
        customConfigOverrides =
          "sampled.size = " + sampleSize + "\n" +
            "sampled.strategy = \"" + sampleStrategy + "\"\n" +
            "maximum-size = " + maxSize;
        // Parse the custom configuration overrides
        customConfig = ConfigFactory.parseString(customConfigOverrides);
        // Combine the custom configuration with the existing one
        combinedConfig = customConfig.withFallback(this.inConfig);
        // Create a new SampledPolicy with the combined config
        SampledPolicy sampledFifoPolicy = new SampledPolicy(
          Admission.ALWAYS,
          SampledPolicy.EvictionPolicy.FIFO,
          combinedConfig
        );
        return sampledFifoPolicy;
      //--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

        case "LFU":
          // Extract the overriding values from the original config
          sampleSize = this.inConfig.getInt("esp.sampled.size");
          sampleStrategy = this.inConfig.getString("esp.sampled.strategy");
          // Create the custom configuration overrides as a string
          customConfigOverrides =
            "maximum-size = " + maxSize + "\n"+
            "sampled.size = " + sampleSize + "\n" +
              "sampled.strategy = " + sampleStrategy;
          // Parse the custom configuration overrides
          customConfig = ConfigFactory.parseString(customConfigOverrides);
          // Combine the custom configuration with the existing one
          combinedConfig = customConfig.withFallback(this.inConfig);
          // Create a new SampledPolicy with the combined config
          SampledPolicy sampledLfuPolicy = new SampledPolicy(
            Admission.ALWAYS,
            SampledPolicy.EvictionPolicy.LFU,
            combinedConfig
          );
          return sampledLfuPolicy;
      //--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      case "SegmentedLRU":
        // Extract the overriding values from the original config
        percentProtected = this.inConfig.getDouble("esp.segmented-lru.percent-protected");
        // Create the custom configuration overrides as a string
        customConfigOverrides =
          "maximum-size = " + maxSize + "\n"+
            "percent-protected = " + percentProtected;
        System.out.println(customConfigOverrides);

        // Parse the custom configuration overrides
        customConfig = ConfigFactory.parseString(customConfigOverrides);
        // Combine the custom configuration with the existing one
        combinedConfig = customConfig.withFallback(this.inConfig);
        // Create a new SampledPolicy with the combined config
        SegmentedLruPolicy segmentedLruPolicy = new SegmentedLruPolicy(Admission.ALWAYS, combinedConfig);
//        segmentedLruPolicy.admittor = PipelineTinyLfu.getInstance(this.inConfig, IntraStats);
        segmentedLruPolicy.admittor =  GlobalAdmittor.getInstance(this.inConfig, IntraStats, 3, 1);

        return segmentedLruPolicy;

      //--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      case "LinkedLRU":
        tempLinkedLRUSettings = new BasicSettings(this.inConfig);
        return new LinkedPolicy(tempLinkedLRUSettings.config(),this.characteristics,Admission.ALWAYS,LinkedPolicy.EvictionPolicy.LRU);
      //--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
        case "GDWheel":
        tempGDWheelSettings = new GDWheelSettings(this.inConfig);
        GDWheelPolicy gdwheel = new GDWheelPolicy(tempGDWheelSettings.config());
        gdwheel.maximumSize = 172;
        return gdwheel;
      //--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      case "TwoQueue":
        // Extract the overriding values from the original config
        double percentIn = this.inConfig.getDouble("esp.two-queue.percent-in");
        double percentOut = this.inConfig.getDouble("esp.two-queue.percent-out");
        // Create the custom configuration overrides as a string
        customConfigOverrides =
          "maximum-size = " + maxSize + "\n"+
          "two-queue.percent-in = " + percentIn + "\n" +
            "two-queue.percent-out = " + percentOut;
        // Parse the custom configuration overrides
        customConfig = ConfigFactory.parseString(customConfigOverrides);
        // Combine the custom configuration with the existing one
        combinedConfig = customConfig.withFallback(this.inConfig);
        // Create a new TwoQueuePolicy with the combined config
        TwoQueuePolicy twoQueuePolicy = new TwoQueuePolicy(combinedConfig);
        return twoQueuePolicy;
//--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      case "TuQueue":
        // Extract the overriding values from the original config
        double percentHot = this.inConfig.getDouble("esp.two-queue.percent-hot");
        double percentWarm = this.inConfig.getDouble("esp.two-queue.percent-warm");
        // Create the custom configuration overrides as a string
        customConfigOverrides =
          "maximum-size = " + maxSize + "\n"+
          "two-queue.percent-in = " + percentHot + "\n" +
            "two-queue.percent-out = " + percentWarm;
        // Parse the custom configuration overrides
        customConfig = ConfigFactory.parseString(customConfigOverrides);
        // Combine the custom configuration with the existing one
        combinedConfig = customConfig.withFallback(this.inConfig);
        // Create a new TwoQueuePolicy with the combined config
        TuQueuePolicy tuQueuePolicy = new TuQueuePolicy(combinedConfig);
        return tuQueuePolicy;
      //--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
      default:
        return null;
    }
  }





  private static final class GDWheelSettings extends BasicSettings {
    public GDWheelSettings(Config config) {
      super(config);
    }
    public int numberOfWheels() {
      return config().getInt("esp.gd-wheel.wheels");
    }
    public int numberOfQueues() {
      return config().getInt("esp.gd-wheel.queues");
    }
  }
}

