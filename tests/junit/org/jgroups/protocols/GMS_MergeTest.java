package org.jgroups.protocols;


import org.jgroups.*;
import org.jgroups.conf.ProtocolStackConfigurator;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK;
import org.jgroups.protocols.pbcast.STABLE;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.tests.ChannelTestBase;
import org.jgroups.util.Digest;
import org.jgroups.util.MergeId;
import org.jgroups.util.Util;
import org.testng.annotations.Test;
import org.w3c.dom.Element;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests the GMS protocol for merging functionality
 * @author Bela Ban
 * @version $Id: GMS_MergeTest.java,v 1.23 2010/07/07 09:40:09 belaban Exp $
 */
@Test(groups={Global.STACK_INDEPENDENT}, sequential=true)
public class GMS_MergeTest extends ChannelTestBase {
    static final String simple_props="SHARED_LOOPBACK:PING(timeout=1000):" +
            "pbcast.NAKACK(use_mcast_xmit=false;gc_lag=0;log_discard_msgs=false;log_not_found_msgs=false)" +
            ":UNICAST:pbcast.STABLE(stability_delay=200):pbcast.GMS:FC:FRAG2";

    static final String flush_props=simple_props + ":pbcast.FLUSH";

    static final short GMS_ID=ClassConfigurator.getProtocolId(GMS.class);



    public static void testMergeRequestTimeout() throws Exception {
        _testMergeRequestTimeout(simple_props, "testMergeRequestTimeout");
    }

    public static void testMergeRequestTimeoutWithFlush() throws Exception {
        _testMergeRequestTimeout(flush_props, "testMergeRequestTimeoutWithFlush");
    }

    public static void testSimpleMerge() throws Exception {
        _testSimpleMerge(simple_props, "testSimpleMerge");
    }

    public static void testSimpleMergeWithFlush() throws Exception {
        _testSimpleMerge(flush_props, "testSimpleMergeWithFlush");
    }

    public static void testConcurrentMergeTwoPartitions() throws Exception {
        _testConcurrentMergeTwoPartitions(simple_props, "testConcurrentMergeTwoPartitions");
    }

    public static void testConcurrentMergeTwoPartitionsWithFlush() throws Exception {
        _testConcurrentMergeTwoPartitions(flush_props, "testConcurrentMergeTwoPartitionsWithFlush");
    }

    public static void testConcurrentMergeMultiplePartitions() throws Exception {
        _testConcurrentMergeMultiplePartitions(simple_props, "testConcurrentMergeMultiplePartitions");
    }

    public static void testConcurrentMergeMultiplePartitionsWithFlush() throws Exception {
        _testConcurrentMergeMultiplePartitions(flush_props, "testConcurrentMergeMultiplePartitionsWithFlush");
    }

    public static void testMergeAsymmetricPartitions() throws Exception {
        _testMergeAsymmetricPartitions(simple_props, "testMergeAsymmetricPartitions");
    }

    public static void testMergeAsymmetricPartitionsWithFlush() throws Exception {
        _testMergeAsymmetricPartitions(flush_props, "testMergeAsymmetricPartitionsWithFlush");
    }

    public static void testMergeAsymmetricPartitions2() throws Exception {
        _testMergeAsymmetricPartitions2(simple_props, "testMergeAsymmetricPartitions2");
    }

    public static void testMergeAsymmetricPartitionsWithFlush2() throws Exception {
        _testMergeAsymmetricPartitions2(flush_props, "testMergeAsymmetricPartitionsWithFlush2");
    }
    
    
    /**
     * Simulates the death of a merge leader after having sent a MERG_REQ. Because there is no MergeView or CANCEL_MERGE
     * message, the MergeCanceller has to null merge_id after a timeout
     */
    static void _testMergeRequestTimeout(String props, String cluster_name) throws Exception {
        JChannel c1=new JChannel(props);
        try {
            c1.connect(cluster_name);
            Message merge_request=new Message();
            GMS.GmsHeader hdr=new GMS.GmsHeader(GMS.GmsHeader.MERGE_REQ);
            MergeId new_merge_id=MergeId.create(c1.getAddress());
            hdr.setMergeId(new_merge_id);
            merge_request.putHeader(GMS_ID, hdr);
            GMS gms=(GMS)c1.getProtocolStack().findProtocol(GMS.class);
            gms.setMergeTimeout(2000);
            MergeId merge_id=gms.getMergeId();
            assert merge_id == null;
            System.out.println("starting merge");
            gms.up(new Event(Event.MSG, merge_request));
            merge_id=gms.getMergeId();
            System.out.println("merge_id = " + merge_id);
            assert new_merge_id.equals(merge_id);

            long timeout=(long)(gms.getMergeTimeout() * 1.5);
            System.out.println("sleeping for " + timeout + " ms, then fetching merge_id: should be null (cancelled by the MergeCanceller)");
            long target_time=System.currentTimeMillis() + timeout;
            while(System.currentTimeMillis() < target_time) {
                merge_id=gms.getMergeId();
                if(merge_id == null)
                    break;
                Util.sleep(500);
            }

            merge_id=gms.getMergeId();
            System.out.println("merge_id = " + merge_id);
            assert merge_id == null : "MergeCanceller didn't kick in";
        }
        finally {
            Util.close(c1);
        }
    }


    static void _testSimpleMerge(String props, String cluster_name) throws Exception {
        JChannel[] channels=null;
        try {
            channels=create(props, true, cluster_name, "A", "B", "C", "D");
            print(channels);
            View view=channels[channels.length -1].getView();
            assert view.size() == channels.length : "view is " + view;

            System.out.println("\ncreating partitions: ");
            String[][] partitions=generate(new String[]{"A", "B"}, new String[]{"C", "D"});
            createPartitions(channels, partitions);
            print(channels);
            checkViews(channels, "A", "A", "B");
            checkViews(channels, "B", "A", "B");
            checkViews(channels, "C", "C", "D");
            checkViews(channels, "D", "C", "D");

            Address leader=determineLeader(channels, "A", "C");
            long end_time=System.currentTimeMillis() + 30000;
            do {
                System.out.println("\n==== injecting merge events into " + leader + " ====");
                injectMergeEvent(channels, leader, "A", "C");
                Util.sleep(1000);
                if(allChannelsHaveViewOf(channels, 4))
                    break;
            }
            while(end_time > System.currentTimeMillis());
            
            System.out.println("\n");
            print(channels);
            assertAllChannelsHaveViewOf(channels, 4);
        }
        finally {
            System.out.println("closing channels");
            close(channels);
            System.out.println("done");
        }
    }


    static void _testConcurrentMergeTwoPartitions(String props, String cluster_name) throws Exception {
        JChannel[] channels=null;
        try {
            channels=create(props, true, cluster_name, "A", "B", "C", "D");
            print(channels);
            View view=channels[channels.length -1].getView();
            assert view.size() == channels.length : "view is " + view;

            System.out.println("\ncreating partitions: ");
            String[][] partitions=generate(new String[]{"A", "B"}, new String[]{"C", "D"});
            createPartitions(channels, partitions);
            print(channels);
            checkViews(channels, "A", "A", "B");
            checkViews(channels, "B", "A", "B");
            checkViews(channels, "C", "C", "D");
            checkViews(channels, "D", "C", "D");

            long end_time=System.currentTimeMillis() + 30000;
            do {
                System.out.println("\n==== injecting merge events into A and C concurrently ====");
                injectMergeEvent(channels, "C", "A", "C");
                injectMergeEvent(channels, "A", "A", "C");
                Util.sleep(1000);
                if(allChannelsHaveViewOf(channels, 4))
                    break;
            }
            while(end_time > System.currentTimeMillis());

            System.out.println("\n");
            print(channels);
            assertAllChannelsHaveViewOf(channels, 4);
        }
        finally {
            close(channels);
        }
    }


    static void _testConcurrentMergeMultiplePartitions(String props, String cluster_name) throws Exception {
        JChannel[] channels=null;
        try {
            channels=create(props, true, cluster_name, "A", "B", "C", "D", "E", "F", "G", "H");
            print(channels);
            View view=channels[channels.length -1].getView();
            assert view.size() == channels.length : "view is " + view;
            assertAllChannelsHaveViewOf(channels, 8);

            System.out.println("\ncreating partitions: ");
            String[][] partitions=generate(new String[]{"A", "B"},
                                           new String[]{"C", "D"},
                                           new String[]{"E", "F"},
                                           new String[]{"G", "H"});
            createPartitions(channels, partitions);
            print(channels);
            checkViews(channels, "A", "A", "B");
            checkViews(channels, "B", "A", "B");
            checkViews(channels, "C", "C", "D");
            checkViews(channels, "D", "C", "D");
            checkViews(channels, "E", "E", "F");
            checkViews(channels, "F", "E", "F");
            checkViews(channels, "G", "G", "H");
            checkViews(channels, "H", "G", "H");

            long end_time=System.currentTimeMillis() + 30000;
            do {
                System.out.println("\n==== injecting merge event into A, C, E and G concurrently ====");
                injectMergeEvent(channels, "G", "A", "C", "E", "G");
                injectMergeEvent(channels, "E", "A", "C", "E", "G");
                injectMergeEvent(channels, "A", "A", "C", "E", "G");
                injectMergeEvent(channels, "C", "A", "C", "E", "G");
                Util.sleep(1000);
                if(allChannelsHaveViewOf(channels, 8))
                    break;
            }
            while(end_time > System.currentTimeMillis());

            print(channels);
            assertAllChannelsHaveViewOf(channels, 8);
        }
        finally {
            close(channels);
        }
    }


    /**
     * Tests the merge of the following partitions:
     * <ul>
     * <li>A: {B, A, C}
     * <li>B: {B, C}
     * <li>C: {B, C}
     * </ol>
     * JIRA: https://jira.jboss.org/jira/browse/JGRP-1031
     * @throws Exception
     */
    static void _testMergeAsymmetricPartitions(String props, String cluster_name) throws Exception {
        JChannel[] channels=null;
        MyReceiver[] receivers;
        final int NUM=10;
         try {
             // use simple IDs for UUIDs, so sorting on merge will NOT change the view order
             channels=create(props, true, cluster_name, "B", "A", "C");
             receivers=new MyReceiver[channels.length];
             for(int i=0; i < channels.length; i++) {
                 receivers[i]=new MyReceiver(channels[i].getName());
                 channels[i].setReceiver(receivers[i]);
             }

             JChannel a=findChannel("A", channels), b=findChannel("B", channels), c=findChannel("C", channels);
             print(channels);
             View view=channels[channels.length -1].getView();
             assert view.size() == channels.length : "view is " + view;

             System.out.println("sending " + NUM + " msgs:");
             for(int i=0; i < NUM; i++)
                 for(JChannel ch: channels)
                     ch.send(null, null, "Number #" + i + " from " + ch.getAddress());

             waitForNumMessages(NUM * channels.length, 10000, 1000, receivers);
             checkMessages(NUM * channels.length, receivers);

             System.out.println("\ncreating partitions: ");
             applyView(channels, "A", "B", "A", "C");
             applyView(channels, "B", "B", "C");
             applyView(channels, "C", "B", "C");

             print(channels);
             checkViews(channels, "A", "B", "A", "C");
             checkViews(channels, "B", "B", "C");
             checkViews(channels, "C", "B", "C");

             for(MyReceiver receiver: receivers)
                 receiver.clear();

             DISCARD discard=new DISCARD();
             discard.addIgnoreMember(b.getAddress());
             discard.addIgnoreMember(c.getAddress());

             // A should drop all traffic from B or C
             a.getProtocolStack().insertProtocol(discard, ProtocolStack.ABOVE, SHARED_LOOPBACK.class);

             System.out.println("B and C exchange " + NUM + " messages, A discards them");
             for(int i=0; i < NUM; i++)
                 b.send(null, null, "message #" + i +" from B");
             for(int i=0; i < NUM; i++)
                 c.send(null, null, "message #" + i +" from C");
             waitForNumMessages(NUM * 2, 10000, 500, receivers[0], receivers[2]); // A *does* receiver B's and C's messages !
             checkMessages(NUM * 2, receivers[0], receivers[2]);
             checkMessages(0, receivers[1]);

             Digest da=((NAKACK)a.getProtocolStack().findProtocol(NAKACK.class)).getDigest(),
                     db=((NAKACK)b.getProtocolStack().findProtocol(NAKACK.class)).getDigest(),
                     dc=((NAKACK)c.getProtocolStack().findProtocol(NAKACK.class)).getDigest();

             System.out.println("Digest A: " + da + "\nDigest B: " + db + "\nDigest C: " + dc);
             System.out.println("Running stability protocol on B and C now");

//             a.getProtocolStack().findProtocol(STABLE.class).setLevel("trace");
//             b.getProtocolStack().findProtocol(STABLE.class).setLevel("trace");
//             c.getProtocolStack().findProtocol(STABLE.class).setLevel("trace");

             for(int i=0; i < 3; i++) {
                 ((STABLE)b.getProtocolStack().findProtocol(STABLE.class)).runMessageGarbageCollection();
                 ((STABLE)c.getProtocolStack().findProtocol(STABLE.class)).runMessageGarbageCollection();
                 Util.sleep(300);
             }

             db=((NAKACK)a.getProtocolStack().findProtocol(NAKACK.class)).getDigest();
             db=((NAKACK)b.getProtocolStack().findProtocol(NAKACK.class)).getDigest();
             dc=((NAKACK)c.getProtocolStack().findProtocol(NAKACK.class)).getDigest();
             System.out.println("(after purging)\nDigest A: " + da + "\nDigest B: " + db + "\nDigest C: " + dc);

             // now enable traffic reception of B and C on A:
             discard.removeIgnoredMember(b.getAddress());
             discard.removeIgnoredMember(c.getAddress());

             Address leader=b.getAddress();

             long end_time=System.currentTimeMillis() + 12000;
             do {
                 System.out.println("\n==== injecting merge event into " + leader + " ====");
                 injectMergeEvent(channels, leader, "B", "A", "C");
                 Util.sleep(3000);
                 if(allChannelsHaveView(channels, b.getView()))
                     break;
             }
             while(end_time > System.currentTimeMillis());

             System.out.println("\n");
             print(channels);
             assertAllChannelsHaveView(channels, b.getView());
         }
         finally {
             close(channels);
         }
     }


/**
     * Tests the merge of the following partitions:
     * <ul>
     * <li>A: {A,B}
     * <li>B: {B}
     * </ol>
     * JIRA: https://jira.jboss.org/jira/browse/JGRP-1031
     * @throws Exception
     */
    static void _testMergeAsymmetricPartitions2(String props, String cluster_name) throws Exception {
        JChannel[] channels=null;
        MyReceiver[] receivers;
        final int NUM=10;
         try {
             // use simple IDs for UUIDs, so sorting on merge will NOT change the view order
             channels=create(props, true, cluster_name, "A", "B");
             receivers=new MyReceiver[channels.length];
             for(int i=0; i < channels.length; i++) {
                 receivers[i]=new MyReceiver(channels[i].getName());
                 channels[i].setReceiver(receivers[i]);
             }

             JChannel a=findChannel("A", channels), b=findChannel("B", channels);
             print(channels);
             View view=channels[channels.length -1].getView();
             assert view.size() == channels.length : "view is " + view;

             System.out.println("sending " + NUM + " msgs:");
             for(int i=0; i < NUM; i++)
                 for(JChannel ch: channels)
                     ch.send(null, null, "Number #" + i + " from " + ch.getAddress());

             waitForNumMessages(NUM * channels.length, 10000, 1000, receivers);
             checkMessages(NUM * channels.length, receivers);

             System.out.println("\ncreating partitions: ");
             applyView(channels, "B", "B"); // B has view {B}

             print(channels);
             checkViews(channels, "A", "A", "B"); // A: {A,B}
             checkViews(channels, "B", "B");      // B: {B}

             for(MyReceiver receiver: receivers)
                 receiver.clear();

             Digest da=((NAKACK)a.getProtocolStack().findProtocol(NAKACK.class)).getDigest();
             Digest db=((NAKACK)b.getProtocolStack().findProtocol(NAKACK.class)).getDigest();
             System.out.println("(after purging)\nDigest A: " + da + "\nDigest B: " + db);

             long end_time=System.currentTimeMillis() + 12000;
             do {
                 System.out.println("\n==== injecting merge event ====");
                 injectMergeEvent(channels, a.getAddress(), "A", "B");
                  injectMergeEvent(channels, b.getAddress(), "A", "B");
                 Util.sleep(3000);
                 if(allChannelsHaveView(channels, a.getView()))
                     break;
             }
             while(end_time > System.currentTimeMillis());

            /* long end_time=System.currentTimeMillis() + 12000;
             do {
                 if(allChannelsHaveView(channels, a.getView()))
                     break;
                 Util.sleep(3000);
             }
             while(end_time > System.currentTimeMillis());*/

             System.out.println("\n");
             print(channels);
             assertAllChannelsHaveView(channels, a.getView());
         }
         finally {
             close(channels);
         }
     }



    /**
     * First name is the channel name, the rest is the view to be applied
     * @param members
     */
    private static void applyView(JChannel[] channels, String member, String ... members) throws Exception {
        JChannel ch=findChannel(member, channels);
        View view=createView(members, channels);
        GMS gms=(GMS)ch.getProtocolStack().findProtocol(GMS.class);
        gms.installView(view);
    }


    private static boolean allChannelsHaveViewOf(JChannel[] channels, int count) {
        for(JChannel ch: channels) {
            if(ch.getView().size() != count)
                return false;
        }
        return true;
    }

    private static boolean allChannelsHaveView(JChannel[] channels, View view) {
        for(JChannel ch: channels) {
            if(!ch.getView().equals(view))
                return false;
        }
        return true;
    }

    private static void assertAllChannelsHaveView(JChannel[] channels, View view) {
        for(JChannel ch: channels) {
            View v=ch.getView();
            assert v.equals(view) : "expected view " + view + " but got " + v + " for channel " + ch.getName();
        }
    }

    private static void assertAllChannelsHaveViewOf(JChannel[] channels, int count) {
        for(JChannel ch: channels)
            assert ch.getView().size() == count : ch.getName() + " has view " + ch.getView() + " (should have " + count + " mbrs)";
    }


    private static void close(JChannel[] channels) {
        if(channels == null) return;
        for(int i=channels.length -1; i >= 0; i--) {
            JChannel ch=channels[i];
            Util.close(ch);
        }
    }


    /*private static JChannel[] create(String cluster_name, String ... names) throws Exception {
        return create(false, cluster_name, names);
    }*/

    private static JChannel[] create(String props, boolean simple_ids, String cluster_name, String ... names) throws Exception {
        JChannel[] retval=new JChannel[names.length];
        for(int i=0; i < retval.length; i++) {
            JChannel ch;
            if(simple_ids) {
                ch=new MyChannel(props);
                ((MyChannel)ch).setId(i+1);
            }
            else
                ch=new JChannel(props);
            ch.setName(names[i]);
            retval[i]=ch;
            ch.connect(cluster_name);
            if(i == 0)
                Util.sleep(3000);
        }
        return retval;
    }

    private static void createPartitions(JChannel[] channels, String[]... partitions) throws Exception {
        checkUniqueness(partitions);
        List<View> views=new ArrayList<View>(partitions.length);
        for(String[] partition: partitions) {
            View view=createView(partition, channels);
            views.add(view);
        }
        applyViews(views, channels);
    }

    private static void injectMergeEvent(JChannel[] channels, String leader, String ... coordinators) {
        Address leader_addr=leader != null? findAddress(leader, channels) : determineLeader(channels);
        injectMergeEvent(channels, leader_addr, coordinators);
    }

    private static void injectMergeEvent(JChannel[] channels, Address leader_addr, String ... coordinators) {
        Map<Address,View> views=new HashMap<Address,View>();
        for(String tmp: coordinators) {
            Address coord=findAddress(tmp, channels);
            views.put(coord, findView(tmp, channels));
        }

        JChannel coord=findChannel(leader_addr, channels);
        GMS gms=(GMS)coord.getProtocolStack().findProtocol(GMS.class);
        gms.setLevel("trace");
        gms.up(new Event(Event.MERGE, views));
    }

    private static Address determineLeader(JChannel[] channels, String ... coords) {
        Membership membership=new Membership();
        for(String coord: coords)
            membership.add(findAddress(coord, channels));
        membership.sort();
        return membership.elementAt(0);
    }


    private static String[][] generate(String[] ... partitions) {
        String[][] retval=new String[partitions.length][];
        System.arraycopy(partitions, 0, retval, 0, partitions.length);
        return retval;
    }

    private static void checkUniqueness(String[] ... partitions) throws Exception {
        Set<String> set=new HashSet<String>();
        for(String[] partition: partitions) {
            for(String tmp: partition) {
                if(!set.add(tmp))
                    throw new Exception("partitions are overlapping: element " + tmp + " is in multiple partitions");
            }
        }
    }

    private static View createView(String[] partition, JChannel[] channels) throws Exception {
        Vector<Address> members=new Vector<Address>(partition.length);
        for(String tmp: partition) {
            Address addr=findAddress(tmp, channels);
            if(addr == null)
                throw new Exception(tmp + " not associated with a channel");
            members.add(addr);
        }
        return new View(members.firstElement(), 10, members);
    }

    private static void checkViews(JChannel[] channels, String channel_name, String ... members) {
        JChannel ch=findChannel(channel_name, channels);
        View view=ch.getView();
        assert view.size() == members.length : "view is " + view + ", members: " + Arrays.toString(members);
        for(String member: members) {
            Address addr=findAddress(member, channels);
            assert view.getMembers().contains(addr) : "view " + view + " does not contain " + addr;
        }
    }

    private static JChannel findChannel(String tmp, JChannel[] channels) {
        for(JChannel ch: channels) {
            if(ch.getName().equals(tmp))
                return ch;
        }
        return null;
    }

    private static JChannel findChannel(Address addr, JChannel[] channels) {
        for(JChannel ch: channels) {
            if(ch.getAddress().equals(addr))
                return ch;
        }
        return null;
    }

    private static Address findAddress(String tmp, JChannel[] channels) {
        for(JChannel ch: channels) {
            if(ch.getName().equals(tmp))
                return ch.getAddress();
        }
        return null;
    }

    private static View findView(String tmp, JChannel[] channels) {
        for(JChannel ch: channels) {
            if(ch.getName().equals(tmp))
                return ch.getView();
        }
        return null;
    }

    private static void applyViews(List<View> views, JChannel[] channels) {
        for(View view: views) {
            Collection<Address> members=view.getMembers();
            for(JChannel ch: channels) {
                Address addr=ch.getAddress();
                if(members.contains(addr)) {
                    GMS gms=(GMS)ch.getProtocolStack().findProtocol(GMS.class);
                    gms.installView(view);
                }
            }
        }
    }

    private static void print(JChannel[] channels) {
        for(JChannel ch: channels) {
            System.out.println(ch.getName() + ": " + ch.getView());
        }
    }

    static void waitForNumMessages(int num_msgs, long timeout, long interval, MyReceiver ... receivers) {
        long target_time=System.currentTimeMillis() + timeout;
        while(System.currentTimeMillis() < target_time) {
            boolean all_received=true;
            for(MyReceiver receiver: receivers) {
                if(receiver.getNumMsgs() < num_msgs) {
                    all_received=false;
                    break;
                }
            }
            if(all_received)
                break;
            Util.sleep(interval);
        }
    }

    static void checkMessages(int expected, MyReceiver ... receivers) {
        for(MyReceiver receiver: receivers)
            System.out.println(receiver.name + ": " + receiver.getNumMsgs());
        for(MyReceiver receiver: receivers)
            assert receiver.getNumMsgs() == expected : "[" + receiver.name + "] expected " + expected +
                    " msgs, but received " + receiver.getNumMsgs();
    }


    private static class MyChannel extends JChannel {
        protected int id=0;

        public MyChannel() throws ChannelException {
            super();
        }

        public MyChannel(File properties) throws ChannelException {
            super(properties);
        }

        public MyChannel(Element properties) throws ChannelException {
            super(properties);
        }

        public MyChannel(URL properties) throws ChannelException {
            super(properties);
        }

        public MyChannel(String properties) throws ChannelException {
            super(properties);
        }

        public MyChannel(ProtocolStackConfigurator configurator) throws ChannelException {
            super(configurator);
        }

        public MyChannel(JChannel ch) throws ChannelException {
            super(ch);
        }


        public void setId(int id) {
            this.id=id;
        }

        protected void setAddress() {
            org.jgroups.util.UUID old_addr=local_addr;
            local_addr=new org.jgroups.util.UUID(id, id);

            if(old_addr != null)
                down(new Event(Event.REMOVE_ADDRESS, old_addr));
            if(name == null || name.length() == 0) // generate a logical name if not set
                name=Util.generateLocalName();
            if(name != null && name.length() > 0)
                org.jgroups.util.UUID.add(local_addr, name);

            Event evt=new Event(Event.SET_LOCAL_ADDRESS, local_addr);
            down(evt);
            if(up_handler != null)
                up_handler.up(evt);
        }
    }

    private static class MyReceiver extends ReceiverAdapter {
        private final String name;
        private AtomicInteger num_msgs=new AtomicInteger(0);

        public MyReceiver(String name) {
            this.name=name;
        }

        public int getNumMsgs() {return num_msgs.get();}

        public void clear() {num_msgs.set(0);}

        public void receive(Message msg) {
            num_msgs.incrementAndGet();
        }

        public void viewAccepted(View new_view) {
            System.out.println("[" + name + "] view=" + new_view);
        }
    }

}