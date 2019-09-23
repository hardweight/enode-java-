package org.enodeframework.infrastructure;

import java.util.ArrayList;
import java.util.List;

/**
 * @author anruence@gmail.com
 */
public class MessageHandlerData<T extends IObjectProxy> {
    public List<T> AllHandlers = new ArrayList<>();
    public List<T> ListHandlers = new ArrayList<>();
    public List<T> QueuedHandlers = new ArrayList<>();
}