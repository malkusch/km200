package de.malkusch.km200;

/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */

/**
 * This class was taken from the OpenHAB 1.x Buderus / KM200 binding, and modified to run without the OpenHAB infrastructure.
 */
final class KM200CommObject
{
    private Integer readable;
    private Integer writeable;
    private Integer recordable;
    private String fullServiceName = "";
    private String serviceType = "";
    private Object value = null;
    private Object valueParameter = null;

    public KM200CommObject(String serviceName, String type, Integer read, Integer write, Integer record)
    {
        fullServiceName = serviceName;
        serviceType = type;
        readable = read;
        writeable = write;
        recordable = record;
    }

    public KM200CommObject(String serviceName, String type, Integer write, Integer record)
    {
        fullServiceName = serviceName;
        serviceType = type;
        readable = 1;
        writeable = write;
        recordable = record;
    }

    /* Sets */
    public void setValue(Object val)
    {
        value = val;
    }

    public void setValueParameter(Object val)
    {
        valueParameter = val;
    }

    /* gets */
    public Integer getReadable()
    {
        return readable;
    }

    public Integer getWriteable()
    {
        return writeable;
    }

    public Integer getRecordable()
    {
        return recordable;
    }

    public String getServiceType()
    {
        return serviceType;
    }

    public String getFullServiceName()
    {
        return fullServiceName;
    }

    public Object getValue()
    {
        return value;
    }

    public Object getValueParameter()
    {
        return valueParameter;
    }
}