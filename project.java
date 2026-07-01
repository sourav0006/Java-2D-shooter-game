import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;
import javax.swing.*;

public class project extends JPanel implements Runnable {

    // Window dimensions
    public static final int WIDTH = 800;
    public static final int HEIGHT = 600;

    // Game Thread & State
    private Thread gameThread;
    private boolean running = false;
    private boolean gameOver = false;

    // Input States
    private volatile boolean up, down, left, right;
    private volatile int mouseX, mouseY;
    private volatile boolean isShooting;

    // Player Stats
    private double playerX = WIDTH / 2.0;
    private double playerY = HEIGHT / 2.0;
    private int playerRadius = 15;
    private double playerSpeed = 4.0;
    private int playerHp = 5; // Takes 5 hits to die
    private long lastPlayerShotTime = 0;
    private final long PLAYER_SHOOT_COOLDOWN = 200; // ms between shots

    // Score & Display
    private int score = 0;
    private String lastKillText = "";
    private int textFadeTimer = 0;

    // Game Entities
    private ArrayList<Bullet> bullets;
    private ArrayList<Enemy> enemies;
    private Random random;
    private final Object lock = new Object();

    public project() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(new Color(90, 75, 50)); // Base dirt color
        setFocusable(true);

        bullets = new ArrayList<>();
        enemies = new ArrayList<>();
        random = new Random();

        // Keyboard Listeners
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                int key = e.getKeyCode();
                if (key == KeyEvent.VK_W) up = true;
                if (key == KeyEvent.VK_S) down = true;
                if (key == KeyEvent.VK_A) left = true;
                if (key == KeyEvent.VK_D) right = true;
                if (key == KeyEvent.VK_R && gameOver) restartGame(); // Restart on 'R'
            }

            @Override
            public void keyReleased(KeyEvent e) {
                int key = e.getKeyCode();
                if (key == KeyEvent.VK_W) up = false;
                if (key == KeyEvent.VK_S) down = false;
                if (key == KeyEvent.VK_A) left = false;
                if (key == KeyEvent.VK_D) right = false;
            }
        });

        // Mouse Listeners
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                mouseX = e.getX();
                mouseY = e.getY();
            }
            @Override
            public void mouseDragged(MouseEvent e) {
                mouseX = e.getX();
                mouseY = e.getY();
            }
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) isShooting = true;
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) isShooting = false;
            }
        });
    }

    public void startGame() {
        running = true;
        gameThread = new Thread(this);
        gameThread.start();
    }

    private void restartGame() {
        synchronized (lock) {
            playerX = WIDTH / 2.0;
            playerY = HEIGHT / 2.0;
            playerHp = 5;
            score = 0;
            bullets.clear();
            enemies.clear();
            up = down = left = right = false;
            gameOver = false;
            lastKillText = "";
        }
    }

    @Override
    public void run() {
        long lastTime = System.nanoTime();
        double amountOfTicks = 60.0;
        double ns = 1000000000 / amountOfTicks;
        double delta = 0;

        int enemySpawnTimer = 0;

        while (running) {
            long now = System.nanoTime();
            delta += (now - lastTime) / ns;
            lastTime = now;

            while (delta >= 1) {
                if (!gameOver) {
                    updateLogic();

                    // Handle Enemy Spawning
                    enemySpawnTimer++;
                    if (enemySpawnTimer >= 100) { // Spawn an enemy roughly every ~1.5 seconds
                        spawnEnemy();
                        enemySpawnTimer = 0;
                    }
                }
                delta--;
            }
            repaint(); // Render the screen
        }
    }

    private void updateLogic() {
        synchronized (lock) {
            // Player Movement
            if (up && playerY > playerRadius) playerY -= playerSpeed;
            if (down && playerY < HEIGHT - playerRadius) playerY += playerSpeed;
            if (left && playerX > playerRadius) playerX -= playerSpeed;
            if (right && playerX < WIDTH - playerRadius) playerX += playerSpeed;

            // Player Shooting
            long currentTime = System.currentTimeMillis();
            if (isShooting && currentTime - lastPlayerShotTime > PLAYER_SHOOT_COOLDOWN) {
                shootBullet(playerX, playerY, mouseX, mouseY, true);
                lastPlayerShotTime = currentTime;
            }

            // Update Bullets
            Iterator<Bullet> bulletIter = bullets.iterator();
            while (bulletIter.hasNext()) {
                Bullet b = bulletIter.next();
                b.update();
                
                // Remove bullets off-screen
                if (b.x < 0 || b.x > WIDTH || b.y < 0 || b.y > HEIGHT) {
                    bulletIter.remove();
                    continue;
                }

                // Bullet Collisions
                if (b.isPlayerBullet) {
                    boolean hit = false;
                    for (int i = 0; i < enemies.size(); i++) {
                        Enemy e = enemies.get(i);
                        double dist = Math.hypot(b.x - e.x, b.y - e.y);
                        
                        if (dist < e.bodyRadius) {
                            // Check if headshot (inner radius) or body shot (outer radius)
                            if (dist <= e.headRadius) {
                                score += 3; // Headshot = 2 points + 1 extra point
                                triggerKillMessage("HEADSHOT! +3");
                            } else {
                                score += 2; // Normal kill = 2 points
                                triggerKillMessage("KILL +2");
                            }
                            
                            enemies.remove(i);
                            hit = true;
                            break;
                        }
                    }
                    if (hit) bulletIter.remove();
                } else {
                    // Enemy bullet hits player
                    double dist = Math.hypot(b.x - playerX, b.y - playerY);
                    if (dist < playerRadius) {
                        playerHp--;
                        bulletIter.remove();
                        if (playerHp <= 0) {
                            gameOver = true;
                        }
                    }
                }
            }

            // Update Enemies
            Iterator<Enemy> enemyIter = enemies.iterator();
            while (enemyIter.hasNext()) {
                Enemy e = enemyIter.next();
                e.update(playerX, playerY);

                // Enemy Shooting
                if (currentTime - e.lastShotTime > e.shootCooldown) {
                    shootBullet(e.x, e.y, (int)playerX, (int)playerY, false);
                    e.lastShotTime = currentTime;
                    e.shootCooldown = random.nextInt(1000) + 1500; // Randomize next shot 1.5s - 2.5s
                }

                // Enemy touches player
                double dist = Math.hypot(e.x - playerX, e.y - playerY);
                if (dist < playerRadius + e.bodyRadius) {
                    playerHp--;
                    enemyIter.remove();
                    if (playerHp <= 0) {
                        gameOver = true;
                    }
                }
            }

            if (textFadeTimer > 0) textFadeTimer--;
        }
    }

    private void shootBullet(double startX, double startY, int targetX, int targetY, boolean isPlayer) {
        double dx = targetX - startX;
        double dy = targetY - startY;
        double angle = Math.atan2(dy, dx);
        
        double bulletSpeed = isPlayer ? 10.0 : 4.0;
        double vx = Math.cos(angle) * bulletSpeed;
        double vy = Math.sin(angle) * bulletSpeed;
        
        synchronized (lock) {
            bullets.add(new Bullet(startX, startY, vx, vy, isPlayer));
        }
    }

    private void spawnEnemy() {
        double ex = 0, ey = 0;
        int side = random.nextInt(4); // 0=top, 1=right, 2=bottom, 3=left
        
        if (side == 0) { ex = random.nextInt(WIDTH); ey = -20; }
        else if (side == 1) { ex = WIDTH + 20; ey = random.nextInt(HEIGHT); }
        else if (side == 2) { ex = random.nextInt(WIDTH); ey = HEIGHT + 20; }
        else if (side == 3) { ex = -20; ey = random.nextInt(HEIGHT); }

        synchronized (lock) {
            enemies.add(new Enemy(ex, ey));
        }
    }

    private void triggerKillMessage(String msg) {
        lastKillText = msg;
        textFadeTimer = 60; // Display for approx 1 second (60 frames)
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;
        // Smooth rendering
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw Battlefield Background Elements
        Random bgRand = new Random(101010);
        for (int i = 0; i < 50; i++) {
            int cx = bgRand.nextInt(WIDTH);
            int cy = bgRand.nextInt(HEIGHT);
            int size = 30 + bgRand.nextInt(70);
            
            if (i % 3 == 0) {
                // Grass patches
                g2d.setColor(new Color(50, 90, 40, 100));
            } else if (i % 3 == 1) {
                // Mud patches
                g2d.setColor(new Color(60, 45, 30, 150));
            } else {
                // Bomb Craters
                g2d.setColor(new Color(40, 30, 20, 180));
            }
            g2d.fillOval(cx, cy, size, size - bgRand.nextInt(20));
            
            // Inner crater shadow
            if (i % 3 == 2) {
                g2d.setColor(new Color(20, 15, 10, 220));
                g2d.fillOval(cx + size/4, cy + size/4, size/2, size/2);
            }
        }

        synchronized (lock) {
            if (!gameOver) {
                // Draw Player (Soldier)
                double angle = Math.atan2(mouseY - playerY, mouseX - playerX);
                
                AffineTransform oldTransform = g2d.getTransform();
                g2d.translate(playerX, playerY);
                g2d.rotate(angle);
                
                // Draw soldier body (shoulders)
                g2d.setColor(new Color(60, 120, 60)); // Green uniform
                g2d.fillOval(-12, -15, 24, 30);
                
                // Draw arms/hands holding gun
                g2d.setColor(new Color(255, 200, 150)); // Skin color hands
                g2d.fillOval(5, 7, 8, 8); // Right hand
                g2d.fillOval(10, -5, 8, 8); // Left hand
                
                // Draw gun
                g2d.setColor(Color.DARK_GRAY);
                g2d.fillRect(0, 8, 25, 6);
                
                // Draw head
                g2d.setColor(new Color(255, 200, 150)); // Skin color
                g2d.fillOval(-8, -8, 16, 16);
                
                // Draw helmet
                g2d.setColor(new Color(50, 100, 50));
                g2d.fillOval(-7, -7, 14, 14);
                
                g2d.setTransform(oldTransform);

                // Draw Enemies (Chinese Soldiers)
                for (Enemy e : enemies) {
                    AffineTransform oldEnemyTransform = g2d.getTransform();
                    g2d.translate(e.x, e.y);
                    g2d.rotate(e.angle);

                    // Body (Olive uniform)
                    g2d.setColor(new Color(128, 128, 0));
                    g2d.fillOval(-12, -15, 24, 30);
                    
                    // Hands
                    g2d.setColor(new Color(255, 200, 150));
                    g2d.fillOval(5, 7, 8, 8);
                    g2d.fillOval(10, -5, 8, 8);
                    
                    // Gun
                    g2d.setColor(Color.DARK_GRAY);
                    g2d.fillRect(0, 8, 25, 6);
                    
                    // Head (Headshot target)
                    g2d.setColor(new Color(255, 200, 150));
                    g2d.fillOval(-e.headRadius, -e.headRadius, e.headRadius * 2, e.headRadius * 2);
                    
                    // Helmet
                    g2d.setColor(new Color(107, 142, 35));
                    g2d.fillOval(-7, -7, 14, 14);

                    // Red Star on Helmet
                    g2d.setColor(Color.RED);
                    g2d.fillPolygon(
                        new int[] {0, 2, 4, 2, 3, 0, -3, -2, -4, -2}, 
                        new int[] {-5, -3, -3, -1, 3, 1, 3, -1, -3, -3}, 
                        10
                    );
                    
                    g2d.setTransform(oldEnemyTransform);
                }

                // Draw Bullets
                for (Bullet b : bullets) {
                    if (b.isPlayerBullet) g2d.setColor(Color.CYAN);
                    else g2d.setColor(Color.MAGENTA);
                    g2d.fillOval((int)b.x - b.radius, (int)b.y - b.radius, b.radius * 2, b.radius * 2);
                }

                // Draw HUD
                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font("Arial", Font.BOLD, 20));
                g2d.drawString("FULL SCORE: " + score, 20, 30);
                
                // Draw Health Bar
                g2d.drawString("HP: ", 20, 60);
                for(int i = 0; i < 5; i++) {
                    if(i < playerHp) g2d.setColor(Color.GREEN);
                    else g2d.setColor(Color.RED);
                    g2d.fillRect(60 + (i * 25), 45, 20, 20);
                }

                // Draw Floating Kill Text
                if (textFadeTimer > 0) {
                    int alpha = (int)((textFadeTimer / 60.0) * 255);
                    g2d.setColor(new Color(255, 215, 0, alpha)); // Gold color fading out
                    g2d.setFont(new Font("Arial", Font.BOLD, 24));
                    g2d.drawString(lastKillText, (int)playerX - 40, (int)playerY - 30);
                }

            } else {
                // Game Over Screen
                g2d.setColor(Color.RED);
                g2d.setFont(new Font("Arial", Font.BOLD, 50));
                String goText = "GAME OVER";
                int w1 = g2d.getFontMetrics().stringWidth(goText);
                g2d.drawString(goText, (WIDTH - w1) / 2, HEIGHT / 2 - 50);

                g2d.setColor(Color.WHITE);
                g2d.setFont(new Font("Arial", Font.BOLD, 30));
                String scoreText = "FINAL FULL SCORE: " + score;
                int w2 = g2d.getFontMetrics().stringWidth(scoreText);
                g2d.drawString(scoreText, (WIDTH - w2) / 2, HEIGHT / 2 + 10);

                g2d.setFont(new Font("Arial", Font.PLAIN, 20));
                String rText = "Press 'R' to Restart";
                int w3 = g2d.getFontMetrics().stringWidth(rText);
                g2d.drawString(rText, (WIDTH - w3) / 2, HEIGHT / 2 + 60);
            }
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Top-Down Survival Shooter");
            project game = new project();
            frame.add(game);
            frame.pack();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLocationRelativeTo(null); // Center on screen
            frame.setResizable(false);
            frame.setVisible(true);
            game.startGame();
        });
    }

    // --- INNER CLASSES FOR GAME OBJECTS ---

    class Bullet {
        double x, y;
        double vx, vy;
        boolean isPlayerBullet;
        int radius = 4;

        public Bullet(double x, double y, double vx, double vy, boolean isPlayerBullet) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.isPlayerBullet = isPlayerBullet;
        }

        public void update() {
            x += vx;
            y += vy;
        }
    }

    class Enemy {
        double x, y;
        int bodyRadius = 16;
        int headRadius = 6;  // Inner circle, requires precise shot
        double speed = 1.5;
        long lastShotTime;
        long shootCooldown;
        double angle;

        public Enemy(double startX, double startY) {
            this.x = startX;
            this.y = startY;
            this.lastShotTime = System.currentTimeMillis();
            this.shootCooldown = 2000; // default 2 seconds before first shot
        }

        public void update(double targetX, double targetY) {
            // Move towards player
            double dx = targetX - x;
            double dy = targetY - y;
            this.angle = Math.atan2(dy, dx);
            
            // Move slightly slower than bullets
            x += Math.cos(angle) * speed;
            y += Math.sin(angle) * speed;
        }
    }
}