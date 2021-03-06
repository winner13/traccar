/*
 * Copyright 2012 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.protocol;

import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.TimeZone;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.traccar.BaseProtocolDecoder;
import org.traccar.ServerManager;
import org.traccar.helper.Crc;
import org.traccar.helper.Log;
import org.traccar.model.Position;

/**
 * T55 tracker protocol decoder
 */
public class Gt06ProtocolDecoder extends BaseProtocolDecoder {

    private Long deviceId;

    /**
     * Initialize
     */
    public Gt06ProtocolDecoder(ServerManager serverManager) {
        super(serverManager);
    }

    private String readImei(ChannelBuffer buf) {
        int b = buf.readUnsignedByte();
        StringBuilder imei = new StringBuilder();
        imei.append(b & 0x0F);
        for (int i = 0; i < 7; i++) {
            b = buf.readUnsignedByte();
            imei.append((b & 0xF0) >> 4);
            imei.append(b & 0x0F);
        }
        return imei.toString();
    }

    private static final int MSG_LOGIN = 0x01;
    private static final int MSG_GPS = 0x10;
    private static final int MSG_LBS = 0x11;
    private static final int MSG_GPS_LBS = 0x12;
    private static final int MSG_STATUS = 0x13;
    private static final int MSG_STRING = 0x15;
    private static final int MSG_GPS_LBS_STATUS = 0x16;

    private static void sendResponse(Channel channel, int type, int index) {
        if (channel != null) {
            ChannelBuffer response = ChannelBuffers.directBuffer(10);
            response.writeByte(0x78); response.writeByte(0x78); // header
            response.writeByte(0x05); // size
            response.writeByte(type);
            response.writeShort(index);
            response.writeShort(Crc.crc16Ccitt(response.toByteBuffer(2, 4)));
            response.writeByte(0x0D); response.writeByte(0x0A); // ending
            channel.write(response);
        }
    }

    /**
     * Decode message
     */
    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {

        ChannelBuffer buf = (ChannelBuffer) msg;

        buf.skipBytes(2); // header
        int length = buf.readByte(); // size
        int dataLength = length - 5;

        int type = buf.readUnsignedByte();

        if (type == MSG_LOGIN) {
            String imei = readImei(buf);
            try {
                deviceId = getDataManager().getDeviceByImei(imei).getId();
                buf.skipBytes(dataLength - 8);
                sendResponse(channel, type, buf.readUnsignedShort());
            } catch(Exception error) {
                Log.warning("Unknown device - " + imei);
            }
        }

        else if (type == MSG_STATUS) {
            buf.skipBytes(dataLength);
            sendResponse(channel, type, buf.readUnsignedShort());
        }

        else if (type == MSG_GPS || type == MSG_GPS_LBS || type == MSG_GPS_LBS_STATUS) {
            // Create new position
            Position position = new Position();
            position.setDeviceId(deviceId);
            StringBuilder extendedInfo = new StringBuilder("<protocol>gt06</protocol>");

            // Date and time
            Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            time.clear();
            time.set(Calendar.YEAR, 2000 + buf.readUnsignedByte());
            time.set(Calendar.MONTH, buf.readUnsignedByte() - 1);
            time.set(Calendar.DAY_OF_MONTH, buf.readUnsignedByte());
            time.set(Calendar.HOUR, buf.readUnsignedByte());
            time.set(Calendar.MINUTE, buf.readUnsignedByte());
            time.set(Calendar.SECOND, buf.readUnsignedByte());
            position.setTime(time.getTime());

            // GPS length and Satellites count
            int gpsLength = buf.readUnsignedByte();
            extendedInfo.append("<satellites>");
            extendedInfo.append(gpsLength & 0xf);
            extendedInfo.append("</satellites>");
            gpsLength >>= 4;

            // Latitude
            double latitude = buf.readUnsignedInt() / (60.0 * 30000.0);

            // Longitude
            double longitude = buf.readUnsignedInt() / (60.0 * 30000.0);

            // Speed
            position.setSpeed((double) buf.readUnsignedByte());

            // Course and flags
            int union = buf.readUnsignedShort();
            position.setCourse((double) (union & 0x03FF));
            position.setValid((union & 0x1000) != 0);
            if ((union & 0x0400) == 0) latitude = -latitude;
            if ((union & 0x0800) == 0) longitude = -longitude;

            position.setLatitude(latitude);
            position.setLongitude(longitude);
            position.setAltitude(0.0);

            buf.skipBytes(gpsLength - 12); // skip reserved

            if (type == MSG_GPS_LBS || type == MSG_GPS_LBS_STATUS) {

                int lbsLength = 0;
                if (type == MSG_GPS_LBS_STATUS) {
                    lbsLength = buf.readUnsignedByte();
                }

                // Cell information
                extendedInfo.append("<mcc>");
                extendedInfo.append(buf.readUnsignedShort());
                extendedInfo.append("</mcc>");
                extendedInfo.append("<mnc>");
                extendedInfo.append(buf.readUnsignedByte());
                extendedInfo.append("</mnc>");
                extendedInfo.append("<lac>");
                extendedInfo.append(buf.readUnsignedShort());
                extendedInfo.append("</lac>");
                extendedInfo.append("<cell>");
                extendedInfo.append(buf.readUnsignedShort() << 8 + buf.readUnsignedByte());
                extendedInfo.append("</cell>");
                buf.skipBytes(lbsLength - 9);

                // Status
                if (type == MSG_GPS_LBS_STATUS) {
                    int flags = buf.readUnsignedByte(); // TODO parse flags
                    extendedInfo.append("<alarm>true</alarm>");

                    // Voltage
                    position.setPower((double) buf.readUnsignedByte());

                    // GSM signal
                    extendedInfo.append("<gsm>");
                    extendedInfo.append(buf.readUnsignedByte());
                    extendedInfo.append("</gsm>");
                }
            }

            // Index
            if (buf.readableBytes() > 6) {
                buf.skipBytes(buf.readableBytes() - 6);
            }
            int index = buf.readUnsignedShort();
            position.setId((long) index);
            sendResponse(channel, type, index);

            position.setExtendedInfo(extendedInfo.toString());
            return position;
        }

        return null;
    }

}
