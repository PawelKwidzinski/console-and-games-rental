package pl.kwidz.cgr.feedback;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import pl.kwidz.cgr.common.PageResponse;
import pl.kwidz.cgr.exception.OperationNotPermittedException;
import pl.kwidz.cgr.game.Game;
import pl.kwidz.cgr.game.GameRepository;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final GameRepository gameRepository;
    private final FeedbackRepository feedbackRepository;
    private final FeedbackMapper feedbackMapper;

    public Integer save(FeedbackRequest request, Authentication connectedUser) {
        Game game = gameRepository.findById(request.gameId())
                .orElseThrow(() -> new EntityNotFoundException(String.format("No game found with the ID: %d", request.gameId())));
        if (game.isArchived() || !game.isShareable()) {
            throw new OperationNotPermittedException("You cannot give a feedback for archived or not shareable game");
        }

        if (Objects.equals(game.getCreatedBy(), connectedUser.getName())) {
            throw new OperationNotPermittedException("You cannot give a feedback to your own game");
        }
        Feedback feedback = feedbackMapper.toFeedback(request);
        return feedbackRepository.save(feedback).getId();
    }

    public PageResponse<FeedbackResponse> findAllFeedbacksByGame(Integer gameId, int page, int size, Authentication connectedUser) {
        Pageable pageable = PageRequest.of(page, size);

        Page<Feedback> feedbacks = feedbackRepository.findAllByGameId(gameId, pageable);
        List<FeedbackResponse> feedbackResponses = feedbacks.stream()
                .map(feedback -> feedbackMapper.toFeedbackResponse(feedback, connectedUser.getName()))
                .toList();
        return new PageResponse<>(feedbackResponses, feedbacks.getNumber(), feedbacks.getSize(),
                feedbacks.getTotalElements(), feedbacks.getTotalPages(), feedbacks.isFirst(), feedbacks.isLast());
    }
}
