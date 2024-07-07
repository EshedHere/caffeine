package com.github.benmanes.caffeine.cache.simulator.policy.esp;


public class SharedBuffer {
  private static final SharedBuffer instance = new SharedBuffer();
  private static BaseNode buffer = null;

  private int counter=0;
  // Private constructor to prevent external instantiation
  private SharedBuffer() {
    // Initialize the buffer here
    buffer = new BaseNode();
  }

  // Method to get the singleton instance
  public static  SharedBuffer getInstance() {
    return instance;
  }

  // Method to insert data into the shared buffer
  public static synchronized void insertData(BaseNode newData) {
    //print new data
    buffer= newData;
//    System.out.println("Shared buffer key is " + buffer.key);

  }

  // Method to get data from the shared buffer
  public static  BaseNode getData() {
    return buffer;
  }
  public static synchronized long getBufferKey() {
    return buffer.key;
  }
  public static synchronized void incCounter(){
    instance.counter++;
  }
  public static synchronized void resetCounter(){
    instance.counter=0;
  }
  public static synchronized int getCounter(){
    return instance.counter;
  }


}
