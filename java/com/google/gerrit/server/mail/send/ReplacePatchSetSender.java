// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.mail.send;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.NotifyConfig.NotifyType;
import com.google.gerrit.entities.PatchSetApproval;
import com.google.gerrit.entities.Project;
import com.google.gerrit.exceptions.EmailException;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.RecipientType;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.server.util.LabelVote;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Send notice of new patch sets for reviewers. */
public class ReplacePatchSetSender extends ReplyToChangeSender {
  public interface Factory {
    ReplacePatchSetSender create(
        Project.NameKey project, Change.Id changeId, ChangeKind changeKind);
  }

  private final Set<Account.Id> reviewers = new HashSet<>();
  private final Set<Account.Id> extraCC = new HashSet<>();
  private final ChangeKind changeKind;
  private final Set<PatchSetApproval> outdatedApprovals = new HashSet<>();

  @Inject
  public ReplacePatchSetSender(
      EmailArguments args,
      @Assisted Project.NameKey project,
      @Assisted Change.Id changeId,
      @Assisted ChangeKind changeKind) {
    super(args, "newpatchset", newChangeData(args, project, changeId));
    this.changeKind = changeKind;
  }

  @Override
  protected boolean shouldSendMessage() {
    if (changeKind.isTrivialRebase()) {
      return true;
    }

    return super.shouldSendMessage();
  }

  public void addReviewers(Collection<Account.Id> cc) {
    reviewers.addAll(cc);
  }

  public void addExtraCC(Collection<Account.Id> cc) {
    extraCC.addAll(cc);
  }

  public void addOutdatedApproval(@Nullable Collection<PatchSetApproval> outdatedApprovals) {
    if (outdatedApprovals != null) {
      this.outdatedApprovals.addAll(outdatedApprovals);
    }
  }

  @Override
  protected void init() throws EmailException {
    super.init();

    if (fromId != null) {
      // Don't call yourself a reviewer of your own patch set.
      //
      reviewers.remove(fromId);
    }
    if (args.settings.sendNewPatchsetEmails) {
      if (notify.handling() == NotifyHandling.ALL
          || notify.handling() == NotifyHandling.OWNER_REVIEWERS) {
        reviewers.stream().forEach(r -> add(RecipientType.TO, r));
        extraCC.stream().forEach(cc -> add(RecipientType.CC, cc));
      }
      rcptToAuthors(RecipientType.CC);
    }
    bccStarredBy();
    includeWatchers(NotifyType.NEW_PATCHSETS, !change.isWorkInProgress() && !change.isPrivate());
    removeUsersThatIgnoredTheChange();
  }

  @Override
  protected void formatChange() throws EmailException {
    appendText(textTemplate("ReplacePatchSet"));
    if (useHtml()) {
      appendHtml(soyHtmlTemplate("ReplacePatchSetHtml"));
    }
  }

  public ImmutableList<String> getReviewerNames() {
    List<String> names = new ArrayList<>();
    for (Account.Id id : reviewers) {
      if (id.equals(fromId)) {
        continue;
      }
      names.add(getNameFor(id));
    }
    if (names.isEmpty()) {
      return null;
    }
    return names.stream().sorted().collect(toImmutableList());
  }

  private ImmutableList<String> formatOutdatedApprovals() {
    return outdatedApprovals.stream()
        .map(
            outdatedApproval ->
                String.format(
                    "%s by %s",
                    LabelVote.create(outdatedApproval.label(), outdatedApproval.value()).format(),
                    getNameFor(outdatedApproval.accountId())))
        .sorted()
        .collect(toImmutableList());
  }

  @Override
  protected void setupSoyContext() {
    super.setupSoyContext();
    soyContextEmailData.put("reviewerNames", getReviewerNames());
    soyContextEmailData.put("outdatedApprovals", formatOutdatedApprovals());
  }
}
