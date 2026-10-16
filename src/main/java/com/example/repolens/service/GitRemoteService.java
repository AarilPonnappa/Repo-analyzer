package com.example.repolens.service;

import java.util.Collection;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;

/**
 * Asks GitHub for a branch's latest commit SHA without downloading anything (git ls-remote).
 * Used for the cache check: if this commit was already analyzed, the clone is skipped.
 */
@Service
public class GitRemoteService {

    public String latestCommitSha(String ownerRepo, String branch, String token) {
        try {
            Collection<Ref> refs = Git.lsRemoteRepository()
                    .setRemote("https://github.com/" + ownerRepo + ".git")
                    .setHeads(true)
                    .setTimeout(30)
                    .setCredentialsProvider(token == null || token.isBlank() ? null
                            : new UsernamePasswordCredentialsProvider("x-access-token", token))
                    .call();

            String wanted = "refs/heads/" + branch;
            for (Ref ref : refs) {
                if (ref.getName().equals(wanted) && ref.getObjectId() != null) {
                    return ref.getObjectId().getName();
                }
            }
            throw new CloneException("Branch '" + branch + "' was not found in " + ownerRepo + ".");

        } catch (InvalidRemoteException e) {
            throw new CloneException("Repository " + ownerRepo + " was not found.", e);
        } catch (TransportException e) {
            throw new CloneException("GitHub refused access to " + ownerRepo
                    + ". Check that it exists and that the token can read it.", e);
        } catch (GitAPIException e) {
            throw new CloneException("Couldn't reach " + ownerRepo + " on GitHub.", e);
        }
    }
}
