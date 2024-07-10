package com.github.benmanes.caffeine.cache.simulator.admission;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.ControlBuffer;
import com.typesafe.config.Config;

import java.util.Arrays;

public class GlobalAdmittor implements Admittor {

  public static Admittor[] tinyLfuAdmittors = new Admittor[0];
  private final ControlBuffer controlBuffer;
  private final Admittor alwaysAdmittor;
  private static GlobalAdmittor instance;

  public GlobalAdmittor(Config config, PolicyStats policyStats, int numTinyLfuAdmittors, int controlBufferSize) {
    this.controlBuffer = ControlBuffer.getInstance(controlBufferSize);

    // Ensure the tinyLfuAdmittors array is at least as long as the longest row in the ControlBuffer
      tinyLfuAdmittors = new Admittor[numTinyLfuAdmittors];
    for (int i = 0; i < tinyLfuAdmittors.length; i++) {
//      tinyLfuAdmittors[i] =  PipelineTinyLfu.getInstance(config, policyStats);
    }

    this.alwaysAdmittor = Admittor.always();
  }

  public static GlobalAdmittor getInstance(Config config, PolicyStats policyStats, int numTinyLfuAdmittors, int controlBufferSize) {
    if (instance == null) {
      instance = new GlobalAdmittor(config, policyStats, numTinyLfuAdmittors, controlBufferSize);
    }
    return instance;
  }

  @Override
  public boolean admit(long candidateKey, long victimKey) {
    int rowIndex = controlBuffer.getSketchIndex(candidateKey);
    int[] admitFlags = controlBuffer.getRow(rowIndex);

    for (int i = 0; i < admitFlags.length; i++) {
      if (admitFlags[i] == 1) { // Found the first TinyLFU admittor to consult
        return tinyLfuAdmittors[i].admit(candidateKey, victimKey);
      }
    }
    // If no TinyLFU admittors were flagged, use the alwaysAdmittor
    return alwaysAdmittor.admit(candidateKey, victimKey);
  }

  @Override
  public void record(long key) {
    int rowIndex = controlBuffer.getSketchIndex(key);
    int[] recordFlags = controlBuffer.getRow(rowIndex);

    // If all flags are zero, record using alwaysAdmittor
    if (Arrays.stream(recordFlags).allMatch(flag -> flag == 0)) {
      alwaysAdmittor.record(key);
      return;
    }

    // Otherwise, record using the flagged TinyLFU admittors
    for (int i = 0; i < recordFlags.length; i++) {
      if (recordFlags[i] == 1) {
//        System.out.println("Using admittor "+ i);
        tinyLfuAdmittors[i].record(key);
      }
    }
  }

  // Handle access events for record and admit by simply delegating to the corresponding long key methods
  @Override
  public boolean admit(AccessEvent candidate, AccessEvent victim) {
    return admit(candidate.key(), victim.key());
  }

  @Override
  public void record(AccessEvent event) {
    record(event.key());
  }
  public static void recordAll(long key) {
    for (Admittor admittor : tinyLfuAdmittors) {
      if (admittor instanceof PipelineTinyLfu) {
        ((PipelineTinyLfu) admittor).increment(key);
      }
    }
  }
}

