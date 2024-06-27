package com.github.benmanes.caffeine.cache.simulator.policy.esp;

public class ControlBuffer {
  private static ControlBuffer instance = null;
  private int[][] flagMatrix;

  private int counter = 0;

  // Private constructor to prevent external instantiation
  private ControlBuffer(int pipelineLength) {
    // Initialize the matrix here
    this.flagMatrix = new int[pipelineLength][pipelineLength];
    // Initialize it with default values if necessary
    for (int i = 0; i < pipelineLength; i++) {
      for (int j = 0; j < pipelineLength; j++) {
        this.flagMatrix[i][j] = 0;
      }
    }
  }

  // Method to get the singleton instance
  public static synchronized ControlBuffer getInstance(int pipelineLength) {
    if (instance == null) {
      instance = new ControlBuffer(pipelineLength);
    }
    return instance;
  }

  // Method to retrieve a column from the matrix
  public synchronized int[] getColumn(int columnIndex) {
    int[] column = new int[flagMatrix.length];
    for (int i = 0; i < flagMatrix.length; i++) {
      column[i] = flagMatrix[i][columnIndex];
    }
    return column;
  }
  public int getSketchIndex(long key) {
    // Use some algorithm to map a key to a specific sketch index
    // For this example, let's just use modulus based on the number of sketches
    return (int) (key % flagMatrix.length);
  }

  // Method to insert data into a specific position in the matrix
  public synchronized void insertData(int row, int column, int value) {
    if (row < 0 || row >= flagMatrix.length || column < 0 || column >= flagMatrix[row].length) {
      throw new IndexOutOfBoundsException("Invalid row or column index for insertion.");
    }
    flagMatrix[row][column] = value;
  }

  // Counter manipulation methods
  public synchronized void incCounter() {
    this.counter++;
  }

  public synchronized void resetCounter() {
    this.counter = 0;
  }

  public synchronized int getCounter() {
    return this.counter;
  }

  public void insertData(int[][] admissionFlaMat) {
    flagMatrix = admissionFlaMat;
  }

  // Additional methods related to the matrix can be added here...
}
