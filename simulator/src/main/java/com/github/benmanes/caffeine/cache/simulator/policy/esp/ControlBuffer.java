package com.github.benmanes.caffeine.cache.simulator.policy.esp;

public class ControlBuffer {
  private static ControlBuffer instance = null;
  private int[][] flagMatrix;

  private int counter = 0,controlFlag=0;

  // Private constructor to prevent external instantiation
  private ControlBuffer(int pipelineLength) {
    // Initialize the matrix here
    System.out.println(pipelineLength);
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
  public int getSketchIndex(int counter) {
    // Use some algorithm to map a key to a specific sketch index
    // For this example, let's just use modulus based on the number of sketches
    return counter;
  }

  // Method to insert data into a specific position in the matrix
  public synchronized void insertData(int row, int column, int value) {
    if (row < 0 || row >= flagMatrix.length || column < 0 || column >= flagMatrix[row].length) {
      throw new IndexOutOfBoundsException("Invalid row or column index for insertion.");
    }
    flagMatrix[row][column] = value;
  }
  public synchronized int[] getRow(int rowIndex) {
    if (rowIndex < 0 || rowIndex >= flagMatrix.length) {
      throw new IndexOutOfBoundsException("Invalid row index: " + rowIndex);
    }
//    for(int i=0; i<flagMatrix[rowIndex].length; i++){
//      System.out.println(flagMatrix[rowIndex][i]);
//}
//    System.out.println(flagMatrix[rowIndex]);
    return flagMatrix[rowIndex];
  }

  // Checks if a row in the matrix indicates that "always admit" should be used
  public synchronized boolean isAlwaysAdmit(int rowIndex) {
    int[] row = getRow(rowIndex);
    for (int cell : row) {
      if (cell != 0) {
        return false; // If any cell is not 0, then do not always admit
      }
    }
    return true; // If all cells are 0, then always admit
  }


  //The control buffer will check this flag to determine if flagMatrix should be read
  public synchronized int readFlag() {
    return this.controlFlag;
  }

  public static synchronized void setFlag() {
    instance.controlFlag=1;
  }
  public static synchronized int getFlag() {
    return instance.controlFlag;
  }
  public synchronized void resetFlag() {
    this.controlFlag=0;
  }

  public synchronized int getCounter() {
    return this.counter;
  }

  public void insertData(int[][] admissionFlaMat) {
    flagMatrix = admissionFlaMat;
  }

  // Additional methods related to the matrix can be added here...
}
