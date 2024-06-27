package com.github.benmanes.caffeine.cache.simulator.admission;

import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.typesafe.config.Config;
import com.github.benmanes.caffeine.cache.simulator.policy.esp.ControlBuffer;

// Admittor that delegates to multiple other Admittors based on a global ControlBuffer
public class GlobalAdmittor implements Admittor {
  private final Admittor[] admittors;
  private final ControlBuffer controlBuffer;

  // Constructor receives the type of admission to use and the number of sketches/global admittors
  public GlobalAdmittor(Admission admissionType, Config config, PolicyStats policyStats, int controlBufferSize) {
    int numSketches = controlBufferSize; // Let's have one admittor per sketch/control buffer row
    this.controlBuffer = ControlBuffer.getInstance(controlBufferSize);
    this.admittors = new Admittor[numSketches];

    for (int i = 0; i < numSketches; i++) {
      this.admittors[i] = admissionType.from(config, policyStats);
    }
  }

  // Delegate to the appropriate admittor based on the key
  private Admittor getAdmittorForKey(long key) {
    int index = controlBuffer.getSketchIndex(key);
    return admittors[index];
  }

  @Override
  public void record(long key) {
    Admittor admittor = getAdmittorForKey(key);
    admittor.record(key);
  }

  @Override
  public boolean admit(AccessEvent candidate, AccessEvent victim) {
    return false;
  }

  @Override
  public boolean admit(long candidateKey, long victimKey) {
    Admittor candidateAdmittor = getAdmittorForKey(candidateKey);
    Admittor victimAdmittor = getAdmittorForKey(victimKey);

    // If both keys map to the same admittor, use it for admission
    // Otherwise, the decision is less clear. For simplicity, we'll default to letting the candidate in.
    // You might want to handle this case differently depending on your needs.
    if(candidateAdmittor == victimAdmittor) {
      return candidateAdmittor.admit(candidateKey, victimKey);
    } else {
      return true;
    }
  }
  @Override
  public void record(AccessEvent event) {
    long key = event.key(); // Here we assume AccessEvent has a method to retrieve the key
    record(key);
  }

}
