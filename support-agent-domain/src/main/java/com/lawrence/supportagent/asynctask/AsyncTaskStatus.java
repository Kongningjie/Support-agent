package com.lawrence.supportagent.asynctask;

/** 持久化异步任务的一期状态。 */
public enum AsyncTaskStatus { PENDING, RUNNING, RETRY_WAIT, SUCCEEDED, DEAD, CANCELLED }
