/*
 * Copyright 2014-2018 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.client.ClusterException;
import io.aeron.cluster.codecs.RecordingLogDecoder;
import io.aeron.cluster.service.Cluster;
import org.agrona.CloseHelper;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static io.aeron.Aeron.NULL_VALUE;
import static io.aeron.archive.client.AeronArchive.NULL_POSITION;
import static io.aeron.cluster.ClusterMember.compareLog;

/**
 * Election process to determine a new cluster leader.
 */
class Election implements AutoCloseable
{
    static final int ELECTION_STATE_TYPE_ID = 207;

    enum State
    {
        INIT(0),

        CANVASS(1)
        {
            void exit(final Election election)
            {
                election.isStartup(false);
            }
        },

        NOMINATE(2),

        CANDIDATE_BALLOT(3),

        FOLLOWER_BALLOT(4),

        LEADER_TRANSITION(5),

        LEADER_READY(6),

        FOLLOWER_CATCHUP_TRANSITION(7),

        FOLLOWER_CATCHUP(8)
        {
            void exit(final Election election)
            {
                election.closeCatchUp();
            }
        },

        FOLLOWER_TRANSITION(9),

        FOLLOWER_READY(10);

        static final State[] STATES;

        static
        {
            final State[] states = values();
            STATES = new State[states.length];
            for (final State state : states)
            {
                final int code = state.code();
                if (null != STATES[code])
                {
                    throw new ClusterException("code already in use: " + code);
                }

                STATES[code] = state;
            }
        }

        private final int code;

        State(final int code)
        {
            this.code = code;
        }

        void exit(final Election election)
        {
        }

        int code()
        {
            return code;
        }

        static State get(final int code)
        {
            if (code < 0 || code > (STATES.length - 1))
            {
                throw new ClusterException("invalid state counter code: " + code);
            }

            return STATES[code];
        }
    }

    private boolean isStartup;
    private final long statusIntervalMs;
    private final long leaderHeartbeatIntervalMs;
    private final ClusterMember[] clusterMembers;
    private final ClusterMember thisMember;
    private final MemberStatusAdapter memberStatusAdapter;
    private final MemberStatusPublisher memberStatusPublisher;
    private final ConsensusModule.Context ctx;
    private final AeronArchive localArchive;
    private final ConsensusModuleAgent consensusModuleAgent;
    private final Random random;

    private long timeOfLastStateChangeMs;
    private long timeOfLastUpdateMs;
    private long nominationDeadlineMs;
    private long logPosition;
    private long leadershipTermId;
    private long logLeadershipTermId;
    private long candidateTermId = NULL_VALUE;
    private int logSessionId = CommonContext.NULL_SESSION_ID;
    private ClusterMember leaderMember = null;
    private State state = State.INIT;
    private Counter stateCounter;
    private LogCatchup logCatchup;
    private Subscription logSubscription;

    Election(
        final boolean isStartup,
        final long leadershipTermId,
        final long logPosition,
        final ClusterMember[] clusterMembers,
        final ClusterMember thisMember,
        final MemberStatusAdapter memberStatusAdapter,
        final MemberStatusPublisher memberStatusPublisher,
        final ConsensusModule.Context ctx,
        final AeronArchive localArchive,
        final ConsensusModuleAgent consensusModuleAgent)
    {
        this.isStartup = isStartup;
        this.statusIntervalMs = TimeUnit.NANOSECONDS.toMillis(ctx.statusIntervalNs());
        this.leaderHeartbeatIntervalMs = TimeUnit.NANOSECONDS.toMillis(ctx.leaderHeartbeatIntervalNs());
        this.logPosition = logPosition;
        this.logLeadershipTermId = leadershipTermId;
        this.leadershipTermId = leadershipTermId;
        this.clusterMembers = clusterMembers;
        this.thisMember = thisMember;
        this.memberStatusAdapter = memberStatusAdapter;
        this.memberStatusPublisher = memberStatusPublisher;
        this.ctx = ctx;
        this.localArchive = localArchive;
        this.consensusModuleAgent = consensusModuleAgent;
        this.random = ctx.random();
    }

    public void close()
    {
        CloseHelper.close(logCatchup);
        CloseHelper.close(stateCounter);
    }

    int doWork(final long nowMs)
    {
        int workCount = State.INIT == state ? init(nowMs) : 0;
        workCount += memberStatusAdapter.poll();

        switch (state)
        {
            case CANVASS:
                workCount += canvass(nowMs);
                break;

            case NOMINATE:
                workCount += nominate(nowMs);
                break;

            case CANDIDATE_BALLOT:
                workCount += candidateBallot(nowMs);
                break;

            case FOLLOWER_BALLOT:
                workCount += followerBallot(nowMs);
                break;

            case LEADER_TRANSITION:
                workCount += leaderTransition(nowMs);
                break;

            case LEADER_READY:
                workCount += leaderReady(nowMs);
                break;

            case FOLLOWER_CATCHUP_TRANSITION:
                workCount += followerCatchupTransition(nowMs);
                break;

            case FOLLOWER_CATCHUP:
                workCount += followerCatchup(nowMs);
                break;

            case FOLLOWER_TRANSITION:
                workCount += followerTransition(nowMs);
                break;

            case FOLLOWER_READY:
                workCount += followerReady(nowMs);
                break;
        }

        return workCount;
    }

    void onCanvassPosition(final long logLeadershipTermId, final long logPosition, final int followerMemberId)
    {
        clusterMembers[followerMemberId]
            .leadershipTermId(logLeadershipTermId)
            .logPosition(logPosition);

        if (State.LEADER_READY == state && logLeadershipTermId < leadershipTermId)
        {
            publishNewLeadershipTerm(clusterMembers[followerMemberId].publication());
        }
        else if (State.CANVASS != state && logLeadershipTermId > leadershipTermId)
        {
            state(State.CANVASS, ctx.epochClock().time());
        }
    }

    void onRequestVote(
        final long logLeadershipTermId, final long logPosition, final long candidateTermId, final int candidateId)
    {
        if (candidateTermId <= leadershipTermId || candidateTermId <= this.candidateTermId)
        {
            placeVote(candidateTermId, candidateId, false);
        }
        else if (compareLog(this.logLeadershipTermId, this.logPosition, logLeadershipTermId, logPosition) > 0)
        {
            this.candidateTermId = candidateTermId;
            ctx.clusterMarkFile().candidateTermId(candidateTermId);
            state(State.CANVASS, ctx.epochClock().time());

            placeVote(candidateTermId, candidateId, false);
        }
        else
        {
            this.candidateTermId = candidateTermId;
            ctx.clusterMarkFile().candidateTermId(candidateTermId);
            state(State.FOLLOWER_BALLOT, ctx.epochClock().time());

            placeVote(candidateTermId, candidateId, true);
        }
    }

    void onVote(
        final long candidateTermId,
        final long logLeadershipTermId,
        final long logPosition,
        final int candidateMemberId,
        final int followerMemberId,
        final boolean vote)
    {
        if (Cluster.Role.CANDIDATE == consensusModuleAgent.role() &&
            candidateTermId == this.candidateTermId &&
            candidateMemberId == thisMember.id())
        {
            clusterMembers[followerMemberId]
                .candidateTermId(candidateTermId)
                .leadershipTermId(logLeadershipTermId)
                .logPosition(logPosition)
                .vote(vote ? Boolean.TRUE : Boolean.FALSE);
        }
    }

    void onNewLeadershipTerm(
        final long logLeadershipTermId,
        final long logPosition,
        final long leadershipTermId,
        final int leaderMemberId,
        final int logSessionId)
    {
        if ((State.FOLLOWER_BALLOT == state || State.CANDIDATE_BALLOT == state) &&
            leadershipTermId == this.candidateTermId)
        {
            this.leadershipTermId = leadershipTermId;
            this.candidateTermId = NULL_VALUE;
            leaderMember = clusterMembers[leaderMemberId];
            this.logSessionId = logSessionId;

            if (this.logPosition < logPosition && null == logCatchup)
            {
                logCatchup = new LogCatchup(
                    localArchive,
                    memberStatusPublisher,
                    clusterMembers,
                    leaderMemberId,
                    thisMember.id(),
                    logSessionId,
                    leadershipTermId,
                    consensusModuleAgent.logRecordingId(),
                    this.logPosition,
                    consensusModuleAgent,
                    ctx);

                state(State.FOLLOWER_CATCHUP_TRANSITION, ctx.epochClock().time());
            }
            else
            {
                state(State.FOLLOWER_TRANSITION, ctx.epochClock().time());
            }
        }
        else if (0 != compareLog(this.logLeadershipTermId, this.logPosition, logLeadershipTermId, logPosition))
        {
            if (this.logLeadershipTermId < logLeadershipTermId)
            {
                this.leadershipTermId = this.logLeadershipTermId;
                this.candidateTermId = NULL_VALUE;
                leaderMember = clusterMembers[leaderMemberId];
                this.logSessionId = logSessionId;

                logCatchup = new LogCatchup(
                    localArchive,
                    memberStatusPublisher,
                    clusterMembers,
                    leaderMemberId,
                    thisMember.id(),
                    logSessionId,
                    leadershipTermId,
                    consensusModuleAgent.logRecordingId(),
                    this.logPosition,
                    consensusModuleAgent,
                    ctx);

                state(State.FOLLOWER_CATCHUP_TRANSITION, ctx.epochClock().time());
            }

            // TODO: state may be out of step which requires log truncation and recovery.
        }
    }

    void onRecordingLog(final RecordingLogDecoder decoder)
    {
        if (null != logCatchup)
        {
            logCatchup.onLeaderRecordingLog(decoder);
        }
    }

    void onAppendedPosition(final long leadershipTermId, final long logPosition, final int followerMemberId)
    {
        clusterMembers[followerMemberId]
            .logPosition(logPosition)
            .leadershipTermId(leadershipTermId);
    }

    void onCommitPosition(final long leadershipTermId, final long logPosition, final int leaderMemberId)
    {
        if (leadershipTermId > this.leadershipTermId)
        {
            // TODO: query leader recording log and catch up
        }
    }

    void onReplayNewLeadershipTermEvent(final long leadershipTermId, final long logPosition, final long nowMs)
    {
        if (State.FOLLOWER_CATCHUP == state)
        {
            this.logLeadershipTermId = leadershipTermId;
            this.logPosition = logPosition;

            ctx.recordingLog().appendTerm(consensusModuleAgent.logRecordingId(), leadershipTermId, logPosition, nowMs);
        }
    }

    void closeCatchUp()
    {
        CloseHelper.close(logCatchup);
        logCatchup = null;
    }

    void isStartup(final boolean isStartup)
    {
        this.isStartup = isStartup;
    }

    State state()
    {
        return state;
    }

    ClusterMember leader()
    {
        return leaderMember;
    }

    long leadershipTermId()
    {
        return leadershipTermId;
    }

    long candidateTermId()
    {
        return candidateTermId;
    }

    void logSessionId(final int logSessionId)
    {
        this.logSessionId = logSessionId;
    }

    long logPosition()
    {
        return logPosition;
    }

    private int init(final long nowMs)
    {
        stateCounter = ctx.aeron().addCounter(0, "Election State");

        if (!isStartup)
        {
            logPosition = consensusModuleAgent.prepareForElection(logPosition);
        }

        if (clusterMembers.length == 1)
        {
            candidateTermId = leadershipTermId + 1;
            leaderMember = thisMember;
            state(State.LEADER_TRANSITION, nowMs);
        }
        else if (ctx.appointedLeaderId() == thisMember.id())
        {
            nominationDeadlineMs = nowMs;
            state(State.NOMINATE, nowMs);
        }
        else
        {
            candidateTermId = ctx.clusterMarkFile().candidateTermId();
            state(State.CANVASS, nowMs);
        }

        return 1;
    }

    private int canvass(final long nowMs)
    {
        int workCount = 0;

        if (nowMs >= (timeOfLastUpdateMs + statusIntervalMs))
        {
            timeOfLastUpdateMs = nowMs;
            for (final ClusterMember member : clusterMembers)
            {
                if (member != thisMember)
                {
                    memberStatusPublisher.canvassPosition(
                        member.publication(), leadershipTermId, logPosition, thisMember.id());
                }
            }

            workCount += 1;
        }

        if (ctx.appointedLeaderId() != NULL_VALUE)
        {
            return  workCount;
        }

        final long canvassDeadlineMs = (isStartup ?
            TimeUnit.NANOSECONDS.toMillis(ctx.startupStatusTimeoutNs()) :
            TimeUnit.NANOSECONDS.toMillis(ctx.electionTimeoutNs())) +
            timeOfLastStateChangeMs;

        if (ClusterMember.isUnanimousCandidate(clusterMembers, thisMember) ||
            (ClusterMember.isQuorumCandidate(clusterMembers, thisMember) && nowMs >= canvassDeadlineMs))
        {
            nominationDeadlineMs = nowMs + random.nextInt((int)statusIntervalMs);
            state(State.NOMINATE, nowMs);
            workCount += 1;
        }

        return workCount;
    }

    private int nominate(final long nowMs)
    {
        if (nowMs >= nominationDeadlineMs)
        {
            candidateTermId = NULL_VALUE == candidateTermId ? leadershipTermId + 1 : candidateTermId + 1;
            ClusterMember.becomeCandidate(clusterMembers, candidateTermId, thisMember.id());
            ctx.clusterMarkFile().candidateTermId(candidateTermId);
            consensusModuleAgent.role(Cluster.Role.CANDIDATE);

            state(State.CANDIDATE_BALLOT, nowMs);
            return 1;
        }

        return 0;
    }

    private int candidateBallot(final long nowMs)
    {
        int workCount = 0;

        if (ClusterMember.hasWonVoteOnFullCount(clusterMembers, candidateTermId))
        {
            state(State.LEADER_TRANSITION, nowMs);
            leaderMember = thisMember;
            workCount += 1;
        }
        else if (nowMs >= (timeOfLastStateChangeMs + TimeUnit.NANOSECONDS.toMillis(ctx.electionTimeoutNs())))
        {
            if (ClusterMember.hasMajorityVote(clusterMembers, candidateTermId))
            {
                state(State.LEADER_TRANSITION, nowMs);
                leaderMember = thisMember;
            }
            else
            {
                state(State.CANVASS, nowMs);
            }

            workCount += 1;
        }
        else
        {
            for (final ClusterMember member : clusterMembers)
            {
                if (!member.isBallotSent())
                {
                    workCount += 1;
                    member.isBallotSent(memberStatusPublisher.requestVote(
                        member.publication(), leadershipTermId, logPosition, candidateTermId, thisMember.id()));
                }
            }
        }

        return workCount;
    }

    private int followerBallot(final long nowMs)
    {
        int workCount = 0;

        if (nowMs >= (timeOfLastStateChangeMs + TimeUnit.NANOSECONDS.toMillis(ctx.electionTimeoutNs())))
        {
            state(State.CANVASS, nowMs);
            workCount += 1;
        }

        return workCount;
    }

    private int leaderTransition(final long nowMs)
    {
        for (long termId = leadershipTermId + 1; termId < candidateTermId; termId++)
        {
            ctx.recordingLog().appendTerm(NULL_VALUE, termId, logPosition, nowMs);
        }

        leadershipTermId = candidateTermId;
        candidateTermId = NULL_VALUE;
        consensusModuleAgent.becomeLeader();

        ctx.recordingLog().appendTerm(consensusModuleAgent.logRecordingId(), leadershipTermId, logPosition, nowMs);
        ctx.clusterMarkFile().candidateTermId(NULL_VALUE);

        ClusterMember.resetLogPositions(clusterMembers, NULL_POSITION);
        clusterMembers[thisMember.id()].logPosition(logPosition);
        state(State.LEADER_READY, nowMs);

        return 1;
    }

    private int leaderReady(final long nowMs)
    {
        int workCount = 0;

        if (ClusterMember.haveVotersReachedPosition(clusterMembers, logPosition, leadershipTermId))
        {
            if (consensusModuleAgent.electionComplete(nowMs))
            {
                close();
            }

            workCount += 1;
        }
        else if (nowMs > (timeOfLastUpdateMs + leaderHeartbeatIntervalMs))
        {
            timeOfLastUpdateMs = nowMs;

            for (final ClusterMember member : clusterMembers)
            {
                if (member != thisMember)
                {
                    publishNewLeadershipTerm(member.publication());
                }
            }

            workCount += 1;
        }

        return workCount;
    }

    private int followerCatchupTransition(final long nowMs)
    {
        ensureSubscriptionsCreated();
        logCatchup.connect(logSubscription);
        state(State.FOLLOWER_CATCHUP, nowMs);

        return 1;
    }

    private int followerCatchup(final long nowMs)
    {
        int workCount = 0;

        if (!logCatchup.isDone())
        {
            workCount += memberStatusAdapter.poll();
            workCount += logCatchup.doWork();
            consensusModuleAgent.catchupLogPoll(logCatchup.targetPosition());
        }
        else
        {
            logPosition = logCatchup.targetPosition();

            addLiveLogDestination(false);
            appendTerm(nowMs);

            state(State.FOLLOWER_READY, nowMs);
            workCount += 1;
        }

        return  workCount;
    }

    private int followerTransition(final long nowMs)
    {
        ensureSubscriptionsCreated();

        addLiveLogDestination(true);
        appendTerm(nowMs);

        state(State.FOLLOWER_READY, nowMs);

        return 1;
    }

    private int followerReady(final long nowMs)
    {
        int workCount = 1;
        final Publication publication = leaderMember.publication();

        if (memberStatusPublisher.appendedPosition(publication, leadershipTermId, logPosition, thisMember.id()))
        {
            if (consensusModuleAgent.electionComplete(nowMs))
            {
                close();
            }

            workCount += 0;
        }
        else if (nowMs >= (timeOfLastStateChangeMs + TimeUnit.NANOSECONDS.toMillis(ctx.electionTimeoutNs())))
        {
            state(State.CANVASS, nowMs);
            workCount += 1;
        }

        return workCount;
    }

    private void state(final State state, final long nowMs)
    {
        //System.out.println(this.state + " -> " + state);
        timeOfLastStateChangeMs = nowMs;
        this.state.exit(this);
        this.state = state;
        stateCounter.setOrdered(state.code());

        if (State.CANVASS == state)
        {
            ClusterMember.reset(clusterMembers);
            thisMember.leadershipTermId(leadershipTermId).logPosition(logPosition);
            consensusModuleAgent.role(Cluster.Role.FOLLOWER);
        }
    }

    private void placeVote(final long candidateTermId, final int candidateId, final boolean vote)
    {
        memberStatusPublisher.placeVote(
            clusterMembers[candidateId].publication(),
            candidateTermId,
            logLeadershipTermId,
            logPosition,
            candidateId,
            thisMember.id(),
            vote);
    }

    private void publishNewLeadershipTerm(final Publication publication)
    {
        memberStatusPublisher.newLeadershipTerm(
            publication,
            logLeadershipTermId,
            logPosition,
            leadershipTermId,
            thisMember.id(),
            logSessionId);
    }

    private void ensureSubscriptionsCreated()
    {
        final ChannelUri logChannelUri = followerLogChannel(ctx.logChannel(), logSessionId);

        logSubscription = consensusModuleAgent.createAndRecordLogSubscriptionAsFollower(
            logChannelUri.toString(), logPosition);
        consensusModuleAgent.awaitServicesReady(logChannelUri, logSessionId);
    }

    private void ensureLogImageAvailable()
    {
        consensusModuleAgent.awaitImageAndCreateFollowerLogAdapter(logSubscription, logSessionId);
    }

    private void addLiveLogDestination(final boolean ensureImageAvailable)
    {
        consensusModuleAgent.updateMemberDetails();

        final ChannelUri channelUri = followerLogDestination(ctx.logChannel(), thisMember.logEndpoint());
        logSubscription.addDestination(channelUri.toString());

        if (ensureImageAvailable)
        {
            ensureLogImageAvailable();
        }
    }

    private void appendTerm(final long nowMs)
    {
        ctx.recordingLog().appendTerm(consensusModuleAgent.logRecordingId(), leadershipTermId, logPosition, nowMs);
        ctx.clusterMarkFile().candidateTermId(NULL_VALUE);
    }

    private static ChannelUri followerLogChannel(final String logChannel, final int sessionId)
    {
        final ChannelUri channelUri = ChannelUri.parse(logChannel);
        channelUri.remove(CommonContext.MDC_CONTROL_PARAM_NAME);
        channelUri.put(CommonContext.MDC_CONTROL_MODE_PARAM_NAME, CommonContext.MDC_CONTROL_MODE_MANUAL);
        channelUri.put(CommonContext.SESSION_ID_PARAM_NAME, Integer.toString(sessionId));
        channelUri.put(CommonContext.TAGS_PARAM_NAME, ConsensusModule.Configuration.LOG_SUBSCRIPTION_TAGS);

        return channelUri;
    }

    private static ChannelUri followerLogDestination(final String logChannel, final String logEndpoint)
    {
        final ChannelUri channelUri = ChannelUri.parse(logChannel);
        channelUri.put(CommonContext.ENDPOINT_PARAM_NAME, logEndpoint);

        return channelUri;
    }
}
