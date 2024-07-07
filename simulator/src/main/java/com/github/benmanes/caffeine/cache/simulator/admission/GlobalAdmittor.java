package com.github.benmanes.caffeine.cache.simulator.admission;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.ControlBuffer;
import com.typesafe.config.Config;

import java.util.Arrays;

public class GlobalAdmittor implements Admittor {

  public  final Admittor[] tinyLfuAdmittors;
  private final ControlBuffer controlBuffer;
  private final Admittor alwaysAdmittor;

  public GlobalAdmittor(Config config, PolicyStats policyStats, int numTinyLfuAdmittors, int controlBufferSize) {
    this.controlBuffer = ControlBuffer.getInstance(controlBufferSize);

    // Ensure the tinyLfuAdmittors array is at least as long as the longest row in the ControlBuffer
    int arraySize = numTinyLfuAdmittors;
    this.tinyLfuAdmittors = new Admittor[arraySize];
    for (int i = 0; i < this.tinyLfuAdmittors.length; i++) {
      this.tinyLfuAdmittors[i] = new TinyLfu(config, policyStats);
    }

    this.alwaysAdmittor = Admittor.always();
  }

  @Override
  public boolean admit(long candidateKey, long victimKey) {
    boolean admittedByAnyone = false;
    int rowIndex = controlBuffer.getSketchIndex(candidateKey);
    int[] admitFlags = controlBuffer.getRow(rowIndex);

    // Loop over the admittance flags to determine which TinyLFU to consult
    for (int i = 0; i < admitFlags.length; i++) {
      if (admitFlags[i] == 1) { // If the flag is set, consult the corresponding TinyLFU admittor
        if (!tinyLfuAdmittors[i].admit(candidateKey, victimKey)) {
          return false; // If any flagged TinyLFU rejects, then reject
        }
      }
    }
    // If no TinyLFU admittors were consulted (all flags were 0), or at least one admits, then admit
    return true;
  }

  @Override
  public void record(long key) {
    // Similar logic to admit, applied to the record method

    int rowIndex = controlBuffer.getSketchIndex(key);
    int[] admitFlags = controlBuffer.getRow(rowIndex);
    //print key and admitFlags
    // Use TinyLFU admittors as indicated by flags
    for (int i = 0; i < admitFlags.length; i++) {
      if (admitFlags[i] == 1) { // If the flag is set, use the corresponding TinyLFU admittor
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
}
